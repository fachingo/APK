package ca.canadavhs.snapmobile

/** Explicit local-only recovery. Never resumes a paid or externally writing workflow. */
internal suspend fun CaptureRepository.resumeLocalProduction(id:String) {
 mutate {
  val dao=journal.productionDao();val current=dao.job(id) ?: error("Workflow no longer exists")
  check(current.kind in setOf("MATCH_PRICE","METADATA")) {"AI jobs require a fresh budget authorization"}
  check(current.state=="PAUSED_REVIEW" && id !in activeProduction) {"Workflow state changed; refresh task history"}
  dao.update(current.copy(state="QUEUED",error="",updatedAt=System.currentTimeMillis()))
 }
 enqueueProduction(id)
}
