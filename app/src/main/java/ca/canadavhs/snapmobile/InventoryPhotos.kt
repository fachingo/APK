package ca.canadavhs.snapmobile

import ca.canadavhs.snapmobile.core.*
import java.time.OffsetDateTime

internal suspend fun CaptureRepository.invalidateInventory(groupId: String, action: String) {
    val inventory=journal.inventoryDao(); val old=inventory.item(groupId) ?: return
    val saved=old.copy(ready=false,revision=old.revision+1,updatedAt=OffsetDateTime.now().toString(),shopifyState=if(old.shopifyProductId.isNotBlank()) "LOCAL_CHANGES" else old.shopifyState)
    inventory.update(saved); inventoryAudit(old,saved,action)
}
private suspend fun CaptureRepository.editableInventoryPhotos(groupId: String, allowPending: Boolean = false) {
    check(journal.inventoryDao().item(groupId)!=null) { "Inventory item not found." }
    check(allowPending || dao.pending().isEmpty()) { "Wait for photographs to finish saving." }
    val group=dao.group(groupId)
    if(group.archiveId!=null) {
        val db=journal.openHelper.writableDatabase
        db.query("SELECT state FROM archives WHERE id=?",arrayOf(group.archiveId)).use { cursor ->
            check(!cursor.moveToFirst() || cursor.getString(0)!="BUILDING") { "Wait for the existing capture archive to finish." }
        }
    }
}
suspend fun CaptureRepository.reserveInventoryPhoto(groupId: String, replaceId: String?): PhotoRow = mutate {
    editableInventoryPhotos(groupId, allowPending=true)
    check(journal.inventoryDao().item(groupId)?.archived==false) { "Restore the archived item before adding photos." }
    check(dao.pending().size<3) { "Three photos are saving. Please wait." }
    check(freeSpace(originals)>100L*1024*1024) { "Less than 100 MB free. Back up and free storage before capturing." }
    val active=dao.photos(groupId).filter { !it.deleted }
    val replaced=replaceId?.let { dao.photo(it).also { p -> check(p.groupId==groupId && !p.deleted && p.state=="READY") } }
    val photo=PhotoRow(groupId=groupId,position=(dao.photos(groupId).maxOfOrNull { it.position } ?: -1)+1,role=replaced?.role ?: defaultRole(active.size).name)
    dao.insert(photo); invalidateInventory(groupId,"PHOTO_RESERVED"); photo
}
suspend fun CaptureRepository.replaceInventoryPhoto(oldId: String, newId: String) = mutate {
    val old=dao.photo(oldId); val replacement=dao.photo(newId)
    check(old.groupId==replacement.groupId && !old.deleted && replacement.state=="READY")
    journal.trashDao().target("PHOTO",old.id)?.let {error("Original already in Trash")}
    journal.trashDao().insert(TrashRow(kind="PHOTO",targetId=old.id,label="${old.role} photograph before retake"))
    dao.update(old.copy(deleted=true)); dao.update(replacement.copy(position=old.position,role=old.role))
    invalidateInventory(old.groupId,"PHOTO_RETAKEN")
}
suspend fun CaptureRepository.reviseInventoryPhoto(id:String,action:String,value:String="") {
    if(action=="REMOVE") {trashLocal("PHOTO",id);return}
    if(action=="RESTORE") {restorePhoto(id);return}
    mutate {
    val photo=dao.photo(id); editableInventoryPhotos(photo.groupId)
    val active=dao.photos(photo.groupId).filter { !it.deleted }.toMutableList()
    when(action) {
        "ROLE" -> dao.update(photo.copy(role=Role.valueOf(value).name))
        "REMOVE" -> dao.update(photo.copy(deleted=true))
        "RESTORE" -> dao.update(photo.copy(deleted=false,position=(active.maxOfOrNull { it.position } ?: -1)+1))
        "UP","DOWN" -> {
            val index=active.indexOfFirst { it.id==id }; val next=index+if(action=="UP") -1 else 1
            require(index>=0 && next in active.indices) { "Photo is already at that end." }
            java.util.Collections.swap(active,index,next)
            active.forEachIndexed { i,p -> dao.update(p.copy(position=i)) }
        }
        "MOVE" -> {
            require(value!=photo.groupId); editableInventoryPhotos(value)
            val target=journal.inventoryDao().item(value) ?: error("Target item not found.")
            check(!target.archived); val group=dao.group(value); val source=dao.group(photo.groupId)
            check(group.sessionId==source.sessionId) { "Regroup within the same batch." }
            dao.update(photo.copy(groupId=value,position=(dao.photos(value).maxOfOrNull { it.position } ?: -1)+1))
            invalidateInventory(value,"PHOTO_MOVED_IN")
        }
        else -> error("Unsupported photo correction.")
    }
    invalidateInventory(photo.groupId,"PHOTO_$action")
}
}

suspend fun CaptureRepository.applyQrLocation(value: String) = mutate {
    require(validLocation(value) && value.isNotBlank())
    val group=current(); check(dao.pending().isEmpty()) {"Wait for captures to save."}
    val session=dao.session(group.sessionId)
    dao.update(session.copy(location=value))
    if(dao.photos(group.id).none {!it.deleted}) dao.update(group.copy(location=value))
    dao.insert(EventRow(sessionId=session.id,groupId=group.id,action="QR_LOCATION:$value:${if(dao.photos(group.id).any {!it.deleted}) "AFTER_NEXT" else "NOW"}"))
}
