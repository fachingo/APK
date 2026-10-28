"""Confidential Shopify credentials and durable narrow RPCs; never an inventory backend."""
from __future__ import annotations
import hashlib
import hmac
import json
import os
import secrets
import threading
import time
from dataclasses import dataclass
from urllib.parse import urlencode,urlsplit
import httpx
from .security import State,BrokerError,canonical,digest
from .operations import prepare,uuid,gid,upload_url


@dataclass(frozen=True)
class Config:
    public_url:str
    shop:str
    client_id:str
    client_secret:str
    auth_mode:str="authorization_code"
    same_organization:bool=False
    allow_production:bool=False
    allow_publish:bool=False

    def __post_init__(self):
        import re
        u=urlsplit(self.public_url)
        if u.scheme!="https" or not u.hostname or u.username or u.password or u.path not in ("","/") or u.query or u.fragment or self.public_url.endswith("/"):
            raise ValueError("BROKER_PUBLIC_URL must be an HTTPS origin without trailing slash")
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,62}\.myshopify\.com",self.shop):
            raise ValueError("SHOPIFY_SHOP must be a canonical myshopify.com domain")
        if not self.client_id or not self.client_secret or self.auth_mode not in ("authorization_code","client_credentials"):
            raise ValueError("Configure server-side Shopify app credentials and authentication mode")
        if self.auth_mode=="client_credentials" and not self.same_organization:
            raise ValueError("Client credentials require explicit same-organization configuration")

    @property
    def scopes(self):
        return ["write_products","write_inventory","read_locations","write_files"]+(["read_publications","write_publications"] if self.allow_publish else [])

    @classmethod
    def environment(cls):
        return cls(os.environ["BROKER_PUBLIC_URL"],os.environ["SHOPIFY_SHOP"],os.environ["SHOPIFY_CLIENT_ID"],os.environ["SHOPIFY_CLIENT_SECRET"],
                   os.getenv("SHOPIFY_AUTH_MODE","authorization_code"),os.getenv("SHOPIFY_SAME_ORGANIZATION")=="true",os.getenv("ALLOW_PRODUCTION")=="true",os.getenv("ALLOW_PUBLISH")=="true")


class Service:
    def __init__(self,config:Config,state:State,http:httpx.Client|None=None):
        self.config,self.state=config,state
        self.http=http or httpx.Client(timeout=45,follow_redirects=False)
        self.token_lock=threading.Lock()
        self.health_cache:tuple[float,dict]|None=None

    def token(self,rejected:str="") -> str:
        with self.token_lock:
            saved=self.state.get("shopify-token")
            bound=digest((self.config.client_id+"\n"+self.config.shop+"\n"+self.config.auth_mode).encode())
            if saved and saved.get("clientHash")==bound and (saved.get("expiresAt") is None or saved["expiresAt"]>time.time()+300) and (not rejected or saved["accessToken"]!=rejected):
                return saved["accessToken"]
            if self.config.auth_mode=="client_credentials":
                payload={"grant_type":"client_credentials","client_id":self.config.client_id,"client_secret":self.config.client_secret}
            elif saved and saved.get("refreshToken"):
                payload={"grant_type":"refresh_token","client_id":self.config.client_id,"client_secret":self.config.client_secret,"refresh_token":saved["refreshToken"]}
            else:
                raise BrokerError("AUTHORIZE_REQUIRED","Authorize this Shopify app in the system browser, then test the connection.",401)
            result=self._token_request(payload)
            if self.config.auth_mode=="client_credentials" and "expires_in" not in result:
                raise BrokerError("TOKEN_RESPONSE","Shopify did not return the expected token lifetime.",502)
            self._save_token(result,saved)
            return result["access_token"]

    def _token_request(self,payload:dict)->dict:
        try:
            response=self.http.post(f"https://{self.config.shop}/admin/oauth/access_token",data=payload)
            if response.status_code not in (200,201):
                raise BrokerError("AUTHORIZE_REQUIRED","Shopify token exchange failed. Check the server app configuration or authorize again.",401)
            if len(response.content)>131072:
                raise ValueError()
            result=response.json()
            if not isinstance(result.get("access_token"),str) or not result["access_token"]:
                raise ValueError()
            return result
        except (httpx.HTTPError,ValueError,KeyError):
            raise BrokerError("TOKEN_CONNECTION","Shopify token service is unavailable or returned an invalid response.",502) from None

    def _save_token(self,result:dict,previous:dict|None=None):
        expires=result.get("expires_in")
        if expires is not None and (type(expires) is not int or not 1<=expires<=172800):
            raise BrokerError("TOKEN_RESPONSE","Unsupported Shopify token lifetime.",502)
        saved={"clientHash":digest((self.config.client_id+"\n"+self.config.shop+"\n"+self.config.auth_mode).encode()),"accessToken":result["access_token"],"refreshToken":result.get("refresh_token",(previous or {}).get("refreshToken","")),"expiresAt":time.time()+expires if expires else None,"scope":result.get("scope",(previous or {}).get("scope",""))}
        self.state.put("shopify-token",saved)
        self.health_cache=None

    def auth_start(self,device:str)->dict:
        if self.config.auth_mode=="client_credentials":
            return {"mode":"client_credentials","message":"Configured server obtains same-organization tokens. Test connection."}
        state=secrets.token_urlsafe(32)
        with self.state.connect() as db:
            db.execute("INSERT INTO oauth VALUES(?,?,?,0)",(digest(state.encode()),device,int(time.time())+300))
        url=f"https://{self.config.shop}/admin/oauth/authorize?"+urlencode({"client_id":self.config.client_id,"scope":",".join(self.config.scopes),"redirect_uri":self.config.public_url+"/oauth/callback","state":state})
        return {"mode":"authorization_code","url":url,"expiresIn":300}

    def auth_callback(self,pairs:list[tuple[str,str]]):
        params=dict(pairs)
        if len(params)!=len(pairs) or len(urlencode(pairs))>16384 or params.get("shop")!=self.config.shop:
            raise BrokerError("OAUTH_CALLBACK","Shopify authorization callback was rejected.",401)
        signature=params.pop("hmac","")
        expected=hmac.new(self.config.client_secret.encode(),urlencode(sorted(params.items())).encode(),hashlib.sha256).hexdigest()
        try:
            valid_time=abs(int(params.get("timestamp","0"))-int(time.time()))<=300
        except ValueError:
            valid_time=False
        if not hmac.compare_digest(signature,expected) or not valid_time:
            raise BrokerError("OAUTH_SIGNATURE","Shopify callback signature or timestamp was rejected.",401)
        state_hash=digest(params.get("state","").encode())
        with self.state.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row=db.execute("SELECT * FROM oauth WHERE state_hash=?",(state_hash,)).fetchone()
            if not row or row["used"] or row["expires"]<time.time():
                raise BrokerError("OAUTH_STATE","Authorization expired or was already handled. Start again.",401)
            if not db.execute("SELECT 1 FROM devices WHERE id=? AND revoked=0",(row["device"],)).fetchone():
                raise BrokerError("DEVICE_REVOKED","This phone was revoked. Pair it again.",401)
            db.execute("UPDATE oauth SET used=1 WHERE state_hash=?",(state_hash,))
        result=self._token_request({"client_id":self.config.client_id,"client_secret":self.config.client_secret,"code":params.get("code","")})
        self._save_token(result)

    def graphql(self,query:str,variables:dict)->dict:
        token=self.token()
        for attempt in range(2):
            try:
                response=self.http.post(f"https://{self.config.shop}/admin/api/2026-10/graphql.json",headers={"X-Shopify-Access-Token":token},json={"query":query,"variables":variables})
            except httpx.HTTPError:
                raise BrokerError("SHOPIFY_NETWORK","Shopify connection ended before a confirmed outcome.",502) from None
            if response.status_code==401 and attempt==0:
                token=self.token(rejected=token)
                continue
            if response.status_code==429:
                raise BrokerError("SHOPIFY_THROTTLED","Shopify rate limit reached; the saved job can retry later.",429,60)
            if response.status_code not in (200,201):
                raise BrokerError("SHOPIFY_HTTP","Shopify rejected or could not complete this request (HTTP %d)."%response.status_code,502 if response.status_code>=500 else 403)
            try:
                if len(response.content)>2_000_000:
                    raise ValueError()
                payload=response.json()
            except ValueError:
                raise BrokerError("SHOPIFY_RESPONSE","Shopify returned an invalid or oversized response.",502) from None
            if payload.get("errors"):
                codes={e.get("extensions",{}).get("code") for e in payload["errors"] if isinstance(e,dict)}
                if codes=={"THROTTLED"} and not payload.get("data"):
                    raise BrokerError("SHOPIFY_THROTTLED","Shopify query cost limit reached; retry later.",429,60)
                raise BrokerError("SHOPIFY_GRAPHQL","Shopify rejected the API operation. Review current API fields, scopes and inputs.",422)
            data=payload.get("data")
            if not isinstance(data,dict):
                raise BrokerError("SHOPIFY_RESPONSE","Shopify did not confirm an operation result.",502)
            return data
        raise BrokerError("AUTHORIZE_REQUIRED","Shopify authentication was rejected; authorize again.",401)

    def health(self,force=False):
        if not force and self.health_cache and self.health_cache[0]>time.time()-30:
            return self.health_cache[1]
        op=prepare("health",{},self.config.allow_publish)
        data=self.graphql(op.query,op.variables)
        shop=data.get("shop") or {}
        scopes=[v["handle"] for v in (data.get("currentAppInstallation") or {}).get("accessScopes",[])]
        result={"shopId":shop.get("id"),"shop":self.config.shop,"name":shop.get("name",""),"currency":shop.get("currencyCode"),"developmentStore":bool(shop.get("plan",{}).get("partnerDevelopment")),"scopes":scopes,"locations":data.get("locations",{}).get("nodes",[]),"publications":data.get("publications",{}).get("nodes",[]),"allowPublish":self.config.allow_publish,"allowProduction":self.config.allow_production,"apiVersion":"2026-10","verifiedAt":int(time.time())}
        if not result["shopId"]:
            raise BrokerError("SHOPIFY_RESPONSE","Shopify did not identify the connected store.",502)
        self.health_cache=(time.time(),result)
        return result

    def _write_guard(self,kind,args,operation):
        health=self.health()
        if health["currency"]!="CAD":
            raise BrokerError("CURRENCY_MISMATCH","SnapIMS CAD prices require a CAD development store; no currency conversion was applied.",409)
        if not health["developmentStore"] and not self.config.allow_production:
            raise BrokerError("DEVELOPMENT_ONLY","This broker permits development-store writes only. No production request was sent.",403)
        required={"write_products","write_inventory","write_files"}
        if not required<=set(health["scopes"]):
            raise BrokerError("MISSING_SCOPES","Authorize product, inventory, file and location permissions before uploading.",403)
        if kind in ("activate_product","publish") and not self.config.allow_publish:
            raise BrokerError("PUBLISH_DISABLED","Live publication is disabled at this broker.",403)
        key=operation.scope_key
        if not key:
            return
        resources=[]
        for name,resource in (("productId","Product"),("variantId","ProductVariant"),("inventoryItemId","InventoryItem"),("fileId","MediaImage")):
            if name in args:
                resources.append((gid(args[name],resource),resource))
        if kind=="reorder":
            resources.extend((gid(i,"MediaImage"),"MediaImage") for i in args["fileIds"])
        with self.state.connect() as db:
            if kind=="file_create" and not db.execute("SELECT 1 FROM resources WHERE scope_key=? AND kind='Product'",(key,)).fetchone():
                raise BrokerError("UNOWNED_RESOURCE","Create/reconcile the owned draft before creating its files.",403)
            for identity,resource in resources:
                if not db.execute("SELECT 1 FROM resources WHERE id=? AND scope_key=? AND kind=?",(identity,key,resource)).fetchone():
                    raise BrokerError("UNOWNED_RESOURCE","This broker only modifies resources created or reconciled by this SnapIMS operation.",403)

    def _bind(self,result,kind,key):
        resources=[]
        if kind=="create":
            product=result.get("product") or {}
            if product.get("id"):
                resources.append((product["id"],key,"Product"))
                for variant in product.get("variants",{}).get("nodes",[]):
                    resources += [(variant["id"],key,"ProductVariant"),(variant["inventoryItem"]["id"],key,"InventoryItem")]
        elif kind=="file_create":
            resources=[(file["id"],key,"MediaImage") for file in result.get("files",[])]
        with self.state.connect() as db:
            for row in resources:
                existing=db.execute("SELECT scope_key,kind FROM resources WHERE id=?",(row[0],)).fetchone()
                if existing and tuple(existing)!=row[1:]:
                    raise BrokerError("REMOTE_CONFLICT","Resource identity is already bound to another operation.",409)
                db.execute("INSERT OR IGNORE INTO resources VALUES(?,?,?)",row)

    def _finish(self,identifier,kind,key,result):
        self._bind(result,kind,key)
        # Mutation selections contain IDs/status only, except transient staging credentials
        # and photo identity markers. No product text, pricing or photo bytes are cached.
        with self.state.connect() as db:
            db.execute("UPDATE operations SET state='COMPLETE',result=?,updated=? WHERE id=?",(self.state.encrypt("operation:"+identifier,result),int(time.time()),identifier))
        return result

    def _reconcile(self,identifier,kind,args,context):
        key=context["scopeKey"]
        if kind=="create":
            op=prepare("find",{"itemId":args["itemId"],"creationKey":key})
            data=self.graphql(op.query,op.variables)
            candidates=data.get("products",{}).get("nodes",[])
            matches=[p for p in candidates if (p.get("metafield") or {}).get("value")==key and digest((p.get("identity") or {}).get("value","").encode())==context["itemHash"] and p.get("status")=="DRAFT"]
            if data.get("products",{}).get("pageInfo",{}).get("hasNextPage") or len(matches)!=1:
                raise BrokerError("UNCERTAIN","Draft creation outcome is uncertain. Review/reconcile the remote marker; no second product was created.",409)
            result={"product":{k:matches[0][k] for k in ("id","status","variants")},"reconciled":True}
        elif kind=="file_create":
            op=prepare("files_find",{"filename":args["filename"]})
            data=self.graphql(op.query,op.variables).get("files") or {}
            matches=[f for f in data.get("nodes",[]) if digest(f.get("alt","").encode())==context["altHash"]]
            if data.get("pageInfo",{}).get("hasNextPage") or len(matches)!=1:
                raise BrokerError("UNCERTAIN","File creation outcome is uncertain. No duplicate file was created; review the saved photo marker.",409)
            result={"files":matches,"reconciled":True}
        else:
            raise BrokerError("UNCERTAIN","Operation outcome needs review.",409)
        return self._finish(identifier,kind,key,result)

    def execute(self,device,identifier,kind,args):
        uuid(identifier)
        if kind=="health":
            return self.health(force=True)
        if kind=="recover_file":
            if set(args)!={"pendingOperationId","creationKey","filename","alt"}:
                raise BrokerError("INVALID_INPUT","Invalid file recovery arguments.")
            pending=uuid(args["pendingOperationId"]);key=uuid(args["creationKey"])
            from .operations import text
            name=text(args["filename"],240,1);alt=text(args["alt"],500,1)
            with self.state.connect() as db:
                row=db.execute("SELECT * FROM operations WHERE id=?",(pending,)).fetchone()
            if not row or row["kind"]!="file_create":
                raise BrokerError("UNCERTAIN","No saved file creation was found. Review before re-staging.",409)
            context=json.loads(row["context"])
            if context!={"scopeKey":key,"altHash":digest(alt.encode()),"filenameHash":digest(name.encode())}:
                raise BrokerError("OPERATION_CONFLICT","File recovery identity does not match.",409)
            if row["state"]=="COMPLETE":
                return self.state.decrypt("operation:"+pending,row["result"])
            return self._reconcile(pending,"file_create",args,context)
        operation=prepare(kind,args,self.config.allow_publish)
        if not operation.write:
            data=self.graphql(operation.query,operation.variables)
            return data.get(operation.field) if operation.field else data
        self._write_guard(kind,args,operation)
        request_hash=digest(canonical({"operation":kind,"arguments":args}))
        context={"scopeKey":operation.scope_key}
        if kind=="create":context["itemHash"]=digest(args["itemId"].encode())
        if kind=="file_create":context.update(altHash=digest(args["alt"].encode()),filenameHash=digest(args["filename"].encode()))
        with self.state.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row=db.execute("SELECT * FROM operations WHERE id=?",(identifier,)).fetchone()
            if row:
                if row["kind"]!=kind or row["request_hash"]!=request_hash:
                    raise BrokerError("OPERATION_CONFLICT","Operation key was reused with different input. Review the original job.",409)
                if row["state"]=="COMPLETE":
                    return self.state.decrypt("operation:"+identifier,row["result"])
                if row["state"]=="REJECTED":
                    raise BrokerError("SHOPIFY_REJECTED","This operation was rejected; review inputs and the recorded phone error.",422)
                reconcile=operation.uncertain and row["state"] in ("INFLIGHT","UNCERTAIN")
            else:
                db.execute("INSERT INTO operations(id,kind,request_hash,state,updated,context) VALUES(?,?,?,'INFLIGHT',?,?)",(identifier,kind,request_hash,int(time.time()),json.dumps(context)))
                reconcile=False
        if reconcile:
            return self._reconcile(identifier,kind,args,context)
        dispatched=False
        try:
            # Before first creation, reject any existing exact SKU or owned marker.
            if kind=="create":
                find=prepare("find",{"itemId":args["itemId"],"creationKey":operation.scope_key})
                existing=self.graphql(find.query,find.variables)
                if any(v.get("sku")==args["itemId"] for v in existing.get("variants",{}).get("nodes",[])) or existing.get("products",{}).get("nodes"):
                    raise BrokerError("DUPLICATE_REVIEW","A SKU or operation marker already exists. Reconcile explicitly; no product was overwritten.",409)
            dispatched=True
            data=self.graphql(operation.query,operation.variables)
            result=data.get(operation.field)
            if not isinstance(result,dict):
                raise BrokerError("SHOPIFY_RESPONSE","Shopify did not confirm this mutation's result.",502)
            errors=result.get("userErrors") or []
            if errors:
                raise BrokerError("SHOPIFY_REJECTED","; ".join(str(e.get("message","Shopify rejected an input"))[:300] for e in errors)[:1200],422)
            if kind=="stage":
                targets=result.get("stagedTargets") or []
                if len(targets)!=1:raise BrokerError("SHOPIFY_RESPONSE","Shopify did not return one staging target.",502)
                upload_url(targets[0]["url"]);upload_url(targets[0]["resourceUrl"])
            result.pop("userErrors",None)
            result=self._finish(identifier,kind,operation.scope_key,result)
            self.state.audit(device,kind,"COMPLETE",request_hash)
            return result
        except BrokerError as error:
            uncertain=dispatched and operation.uncertain and error.status>=500
            status="UNCERTAIN" if uncertain else "RETRYABLE" if error.status in (429,502,503) else "REJECTED"
            with self.state.connect() as db:db.execute("UPDATE operations SET state=?,updated=? WHERE id=?",(status,int(time.time()),identifier))
            self.state.audit(device,kind,status,request_hash)
            if uncertain:
                raise BrokerError("UNCERTAIN","Shopify may have accepted this creation. Retry reconciles its marker; it never blindly creates again.",409) from None
            raise
