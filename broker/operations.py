"""Closed Shopify 2026-10 operation registry, ported from SnapIMS 0.15 publishing."""
from __future__ import annotations
import re
from dataclasses import dataclass
from decimal import Decimal
from urllib.parse import urlsplit
from .security import BrokerError,canonical,digest


@dataclass(frozen=True)
class Operation:
    query: str
    variables: dict
    field: str = ""
    write: bool = False
    uncertain: bool = False
    scope_key: str = ""


def text(value, limit=1000, minimum=0):
    if not isinstance(value, str) or not minimum <= len(value) <= limit or "\x00" in value:
        raise BrokerError("INVALID_INPUT", "Invalid text field.")
    return value


def integer(value, low, high):
    if type(value) is not int or not low <= value <= high:
        raise BrokerError("INVALID_INPUT", "Integer field is outside its permitted range.")
    return value


def gid(value, kind):
    if not isinstance(value, str) or not re.fullmatch(r"gid://shopify/"+kind+r"/[0-9]{1,30}", value):
        raise BrokerError("INVALID_ID", "A typed Shopify resource ID is required.")
    return value


def uuid(value):
    import uuid as module
    try:
        if str(module.UUID(value)) == value:
            return value
    except (ValueError, TypeError, AttributeError):
        pass
    raise BrokerError("INVALID_KEY", "A canonical operation UUID is required.")


def sku(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,199}", value):
        raise BrokerError("INVALID_SKU", "SKU must match the immutable SnapIMS Item ID.")
    return value


def upload_url(value):
    text(value, 16000, 1)
    try:
        url = urlsplit(value)
        host = url.hostname or ""
        allowed = any(host == domain or host.endswith("."+domain) for domain in ("googleapis.com","amazonaws.com","shopify.com","shopifycdn.com","shopifycdn.net","shopifycloud.com"))
        if url.scheme != "https" or url.username or url.password or url.port not in (None,443) or url.fragment or not allowed:
            raise ValueError()
    except ValueError:
        raise BrokerError("UPLOAD_HOST", "Shopify returned an unverified upload host; no transfer was sent.") from None
    return value


METADATA_FIELDS={"title":255,"descriptionHtml":60000,"vendor":255,"productType":255}

def metadata_patch(value):
    if not isinstance(value,dict) or not value or not set(value)<=METADATA_FIELDS.keys():
        raise BrokerError("INVALID_PATCH","Choose only title, description, vendor or product type.")
    return {key:text(v,METADATA_FIELDS[key],1 if key=="title" else 0) for key,v in value.items()}

def reviewed_hash(value):
    if not isinstance(value,str) or not re.fullmatch(r"[a-f0-9]{64}",value):
        raise BrokerError("INVALID_VERSION","A fresh reviewed Shopify version is required.")
    return value

def product_version(product):
    return digest(canonical({k:v for k,v in product.items() if k!="reviewVersion"}))

def protected_hash(product,fields):
    return digest(canonical({k:v for k,v in product.items() if k not in set(fields)|{"updatedAt","reviewVersion"}}))


def prepare(kind: str, values: dict, allow_publish=False) -> Operation:
    if not isinstance(values,dict):
        raise BrokerError("INVALID_INPUT","Operation arguments must be an object.")
    def fields(required, optional=()):
        if set(values) != set(required) | (set(values) & set(optional)) or not set(required) <= set(values):
            raise BrokerError("INVALID_INPUT", "Unexpected or missing operation fields.")
    def item_key():
        return uuid(values["creationKey"])
    if kind == "health":
        fields([])
        publication = "publications(first:100){nodes{id name}}" if allow_publish else ""
        return Operation("query SnapIMSConnection{shop{id name myshopifyDomain currencyCode plan{partnerDevelopment}} currentAppInstallation{accessScopes{handle}} locations(first:100){nodes{id name isActive}} "+publication+"}",{})
    if kind == "browse":
        fields(["query","status","cursor"])
        query=text(values["query"],200);status=text(values["status"],20);cursor=text(values["cursor"],2000)
        if status not in ("","DRAFT","ACTIVE","ARCHIVED"):
            raise BrokerError("INVALID_STATUS","Choose draft, active, archived or all listings.")
        terms=[]
        if query.strip():terms.append('title:"'+query.strip().replace('\\','\\\\').replace('"','\\"')+'"')
        if status:terms.append("status:"+status.lower())
        return Operation("""query SnapIMSCatalog($query:String!,$after:String){products(first:20,after:$after,query:$query,sortKey:UPDATED_AT,reverse:true){nodes{id title status vendor productType updatedAt identity:metafield(namespace:"snapims",key:"item_id"){value} metafield(namespace:"snapims",key:"creation_key"){value} variants(first:2){nodes{id sku inventoryItem{id}} pageInfo{hasNextPage}}} pageInfo{hasNextPage endCursor}}}""",{"query":" AND ".join(terms),"after":cursor or None},"products")
    if kind in ("claim","metadata_update"):
        fields(["creationKey","itemId","productId","locationId","expectedVersion"]+(["patch"] if kind=="metadata_update" else []))
        key=item_key();sku(values["itemId"]);reviewed_hash(values["expectedVersion"]);gid(values["locationId"],"Location");product=gid(values["productId"],"Product")
        if kind=="claim":
            read=prepare("product",{"productId":product,"locationId":values["locationId"]})
            return Operation(read.query,read.variables,read.field,True,False,key)
        patch=metadata_patch(values["patch"])
        return Operation("mutation SnapIMSMetadata($product:ProductUpdateInput!){productUpdate(product:$product){product{id status updatedAt} userErrors{field message}}}",{"product":{"id":product,**patch}},"productUpdate",True,kind=="metadata_update",key)
    if kind == "find":
        fields(["itemId","creationKey"])
        identity, key=sku(values["itemId"]),item_key()
        return Operation("""query SnapIMSFind($sku:String!,$marker:String!){variants:productVariants(first:10,query:$sku){nodes{id sku inventoryItem{id} product{id title status}} pageInfo{hasNextPage}} products(first:10,query:$marker){nodes{id status metafield(namespace:"snapims",key:"creation_key"){value} identity:metafield(namespace:"snapims",key:"item_id"){value} variants(first:2){nodes{id sku inventoryItem{id}}}} pageInfo{hasNextPage}}}""",{"sku":"sku:"+identity,"marker":"tag:snapims-"+key})
    if kind == "product":
        fields(["productId","locationId"])
        return Operation("""query SnapIMSVerify($id:ID!,$location:ID!){product(id:$id){id title status vendor productType tags descriptionHtml updatedAt identity:metafield(namespace:"snapims",key:"item_id"){value} metafield(namespace:"snapims",key:"creation_key"){value} variants(first:2){nodes{id sku price barcode inventoryItem{id tracked requiresShipping measurement{weight{value unit}} inventoryLevel(locationId:$location){quantities(names:["available"]){name quantity}}}} pageInfo{hasNextPage}} media(first:100){nodes{id alt preview{status}} pageInfo{hasNextPage}}}}""",{"id":gid(values["productId"],"Product"),"location":gid(values["locationId"],"Location")},"product")
    if kind == "quantity":
        fields(["inventoryItemId","locationId"])
        return Operation("""query SnapIMSStock($id:ID!,$location:ID!){inventoryItem(id:$id){inventoryLevel(locationId:$location){quantities(names:["available"]){name quantity}}}}""",{"id":gid(values["inventoryItemId"],"InventoryItem"),"location":gid(values["locationId"],"Location")},"inventoryItem")
    if kind == "stage":
        fields(["filename","bytes"])
        name=text(values["filename"],240,1)
        if not re.fullmatch(r"[A-Za-z0-9._-]+\.jpg",name):
            raise BrokerError("INVALID_FILE","Only owned JPEG filenames can be staged.")
        return Operation("mutation SnapIMSStage($input:[StagedUploadInput!]!){stagedUploadsCreate(input:$input){stagedTargets{url resourceUrl parameters{name value}} userErrors{field message}}}",{"input":[{"filename":name,"fileSize":str(integer(values["bytes"],1,250_000_000)),"mimeType":"image/jpeg","httpMethod":"POST","resource":"IMAGE"}]},"stagedUploadsCreate",True)
    if kind == "create":
        fields(["creationKey","itemId","title","descriptionHtml","vendor","productType","tags"])
        key=item_key();identity=sku(values["itemId"]);tags=values["tags"]
        if not isinstance(tags,list) or len(tags)>99 or any(not isinstance(t,str) or not 1<=len(t)<=255 for t in tags):
            raise BrokerError("INVALID_TAGS","Choose at most 99 tags of at most 255 characters.")
        product={"title":text(values["title"],255,1),"descriptionHtml":text(values["descriptionHtml"],60000),"vendor":text(values["vendor"],255),"productType":text(values["productType"],255),"tags":list(dict.fromkeys(tags+["snapims-"+key])),"status":"DRAFT","metafields":[{"namespace":"snapims","key":"item_id","type":"single_line_text_field","value":identity},{"namespace":"snapims","key":"creation_key","type":"single_line_text_field","value":key}]}
        return Operation("mutation SnapIMSCreate($product:ProductCreateInput!){productCreate(product:$product){product{id status variants(first:1){nodes{id inventoryItem{id}}}} userErrors{field message}}}",{"product":product},"productCreate",True,True,key)
    if kind == "variant":
        fields(["creationKey","productId","variantId","priceCents","barcode"])
        cents=integer(values["priceCents"],0,100_000_000)
        variant={"id":gid(values["variantId"],"ProductVariant"),"price":str(Decimal(cents)/Decimal(100)),"taxable":True}
        barcode=text(values["barcode"],14)
        if barcode and (len(barcode) not in (8,12,13,14) or not barcode.isascii() or not barcode.isdecimal()):
            raise BrokerError("INVALID_BARCODE","Invalid inventory barcode.")
        variant["barcode"]=barcode
        return Operation("mutation SnapIMSVariant($id:ID!,$variants:[ProductVariantsBulkInput!]!){productVariantsBulkUpdate(productId:$id,variants:$variants){productVariants{id} userErrors{field message}}}",{"id":gid(values["productId"],"Product"),"variants":[variant]},"productVariantsBulkUpdate",True,False,item_key())
    if kind == "inventory_item":
        fields(["creationKey","inventoryItemId","itemId","weightGrams"])
        return Operation("mutation SnapIMSItem($id:ID!,$input:InventoryItemInput!){inventoryItemUpdate(id:$id,input:$input){inventoryItem{id} userErrors{field message}}}",{"id":gid(values["inventoryItemId"],"InventoryItem"),"input":{"sku":sku(values["itemId"]),"tracked":True,"requiresShipping":True,"measurement":{"weight":{"value":integer(values["weightGrams"],1,1_000_000),"unit":"GRAMS"}}}},"inventoryItemUpdate",True,False,item_key())
    if kind in ("stock_activate","stock_set"):
        fields(["creationKey","inventoryItemId","locationId","idempotencyKey","quantity"]+(["changeFromQuantity"] if kind=="stock_set" else []))
        key=item_key();item=gid(values["inventoryItemId"],"InventoryItem");location=gid(values["locationId"],"Location");request=uuid(values["idempotencyKey"]);quantity=integer(values["quantity"],1,1)
        if kind=="stock_activate":
            return Operation("mutation SnapIMSActivate($id:ID!,$location:ID!,$available:Int!,$key:String!){inventoryActivate(inventoryItemId:$id,locationId:$location,available:$available) @idempotent(key:$key){inventoryLevel{id} userErrors{field message}}}",{"id":item,"location":location,"available":quantity,"key":request},"inventoryActivate",True,False,key)
        payload={"name":"available","reason":"correction","referenceDocumentUri":"snapims://inventory/"+item,"quantities":[{"inventoryItemId":item,"locationId":location,"quantity":quantity,"changeFromQuantity":integer(values["changeFromQuantity"],0,2_147_483_647)}]}
        return Operation("mutation SnapIMSQuantity($input:InventorySetQuantitiesInput!,$key:String!){inventorySetQuantities(input:$input) @idempotent(key:$key){inventoryAdjustmentGroup{createdAt} userErrors{field message code}}}",{"input":payload,"key":request},"inventorySetQuantities",True,False,key)
    if kind == "file_create":
        fields(["creationKey","source","filename","alt"])
        key=item_key();name=text(values["filename"],240,1);alt=text(values["alt"],500,1)
        return Operation("mutation SnapIMSFile($input:[FileCreateInput!]!){fileCreate(files:$input){files{id alt fileStatus} userErrors{field message}}}",{"input":[{"originalSource":upload_url(values["source"]),"contentType":"IMAGE","filename":name,"alt":alt}]},"fileCreate",True,True,key)
    if kind == "files_find":
        fields(["filename"])
        name=text(values["filename"],240,1)
        if not re.fullmatch(r"[A-Za-z0-9._-]+\.jpg",name):
            raise BrokerError("INVALID_FILE","Invalid file identity.")
        return Operation("query SnapIMSFiles($query:String!){files(first:100,query:$query){nodes{id alt fileStatus} pageInfo{hasNextPage}}}",{"query":"filename:"+name},"files")
    if kind == "file_status":
        fields(["fileId"])
        return Operation("query SnapIMSFileState($id:ID!){node(id:$id){...on MediaImage{id alt fileStatus}}}",{"id":gid(values["fileId"],"MediaImage")},"node")
    if kind == "attach":
        fields(["creationKey","productId","fileId"])
        return Operation("mutation SnapIMSAttach($files:[FileUpdateInput!]!){fileUpdate(files:$files){files{id fileStatus} userErrors{field message}}}",{"files":[{"id":gid(values["fileId"],"MediaImage"),"referencesToAdd":[gid(values["productId"],"Product")]}]},"fileUpdate",True,False,item_key())
    if kind == "reorder":
        fields(["creationKey","productId","fileIds"])
        files=values["fileIds"]
        if not isinstance(files,list) or not 1<=len(files)<=100 or len(set(files))!=len(files):
            raise BrokerError("INVALID_FILES","Expected 1–100 distinct intended images.")
        return Operation("mutation SnapIMSOrder($id:ID!,$moves:[MoveInput!]!){productReorderMedia(id:$id,moves:$moves){job{id done} userErrors{field message}}}",{"id":gid(values["productId"],"Product"),"moves":[{"id":gid(file,"MediaImage"),"newPosition":str(i)} for i,file in enumerate(files)]},"productReorderMedia",True,False,item_key())
    if kind == "remote_job":
        fields(["jobId"])
        return Operation("query SnapIMSRemoteJob($id:ID!){job(id:$id){id done}}",{"id":gid(values["jobId"],"Job")},"job")
    if kind in ("activate_product","publish","publication_status"):
        if kind!="publication_status" and not allow_publish:
            raise BrokerError("PUBLISH_DISABLED","Live publication is disabled at this broker.",403)
        fields(["creationKey","productId"]+(["publicationId"] if kind!="activate_product" else []))
        key=item_key();product=gid(values["productId"],"Product")
        if kind=="activate_product":
            return Operation("mutation SnapIMSLive($product:ProductUpdateInput!){productUpdate(product:$product){product{id status} userErrors{field message}}}",{"product":{"id":product,"status":"ACTIVE"}},"productUpdate",True,False,key)
        publication=gid(values["publicationId"],"Publication")
        if kind=="publication_status":
            return Operation("query SnapIMSPublished($id:ID!,$publication:ID!){product(id:$id){id status publishedOnPublication(publicationId:$publication)}}",{"id":product,"publication":publication},"product")
        return Operation("mutation SnapIMSPublish($id:ID!,$input:[PublicationInput!]!){publishablePublish(id:$id,input:$input){publishable{availablePublicationsCount{count}} userErrors{field message}}}",{"id":product,"input":[{"publicationId":publication}]},"publishablePublish",True,False,key)
    raise BrokerError("UNKNOWN_OPERATION","This broker does not expose that operation.",403)
