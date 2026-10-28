"""Mock HTTP only: no real Shopify authorization/store acceptance is claimed."""
import base64
import hashlib
import hmac
import json
import time
import uuid
from urllib.parse import urlencode,urlsplit,parse_qs
import httpx
import pytest
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient
from broker.security import State,b64,canonical,digest,BrokerError
from broker.service import Config,Service
from broker.app import create_app
from broker.operations import prepare


@pytest.fixture
def rig(tmp_path):
    state=State(tmp_path/"broker.sqlite",bytes(range(32)))
    config=Config("https://broker.example.invalid","fixture.myshopify.com","fixture-client","fixture-server-secret",auth_mode="client_credentials",same_organization=True)
    calls=[]
    def upstream(request):
        calls.append(request)
        if request.url.path.endswith("access_token"):
            return httpx.Response(200,json={"access_token":"fixture-shopify-token","expires_in":86399,"scope":"write_products,write_inventory,write_files,read_locations"})
        payload=json.loads(request.content);query=payload["query"]
        if "SnapIMSConnection" in query:
            return httpx.Response(200,json={"data":{"shop":{"id":"gid://shopify/Shop/1","name":"Fixture development store","currencyCode":"CAD","plan":{"partnerDevelopment":True}},"currentAppInstallation":{"accessScopes":[{"handle":s} for s in config.scopes]},"locations":{"nodes":[{"id":"gid://shopify/Location/1","name":"Fixture","isActive":True}]}}})
        if "SnapIMSFind" in query:return httpx.Response(200,json={"data":{"variants":{"nodes":[]},"products":{"nodes":[]}}})
        if "SnapIMSCreate" in query:return httpx.Response(200,json={"data":{"productCreate":{"product":{"id":"gid://shopify/Product/1","status":"DRAFT","variants":{"nodes":[{"id":"gid://shopify/ProductVariant/2","inventoryItem":{"id":"gid://shopify/InventoryItem/3"}}]}},"userErrors":[]}}})
        return httpx.Response(200,json={"data":{}})
    service=Service(config,state,httpx.Client(transport=httpx.MockTransport(upstream)))
    client=TestClient(create_app(service),raise_server_exceptions=False)
    key=ec.generate_private_key(ec.SECP256R1());device=str(uuid.uuid4());public=b64(key.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo))
    pair={"code":state.pairing_code(),"deviceId":device,"publicKey":public}
    assert client.post("/v1/pair",json=pair).status_code==200
    return state,service,client,key,device,calls,pair


def signed(rig,path,payload,nonce=None,timestamp=None):
    _,service,_,key,device,_,_=rig
    body=canonical(payload);stamp=str(int(time.time()) if timestamp is None else timestamp);nonce=nonce or b64(bytes.fromhex(uuid.uuid4().hex+uuid.uuid4().hex))
    message="\n".join(["SNAPIMS1",service.config.public_url,"POST",path,stamp,nonce,digest(body),device]).encode()
    signature=b64(key.sign(message,ec.ECDSA(hashes.SHA256())))
    return body,{"Content-Type":"application/json","X-SnapIMS-Device":device,"X-SnapIMS-Time":stamp,"X-SnapIMS-Nonce":nonce,"X-SnapIMS-Signature":signature}


def rpc(rig,kind,args,operation_id=None):
    payload={"operationId":operation_id or str(uuid.uuid4()),"operation":kind,"arguments":args};body,headers=signed(rig,"/v1/execute",payload)
    return rig[2].post("/v1/execute",content=body,headers=headers)


def create_args():
    return {"creationKey":str(uuid.uuid4()),"itemId":"20261028-A1-001","title":"Synthetic fixture","descriptionHtml":"<p>Fixture only</p>","vendor":"Canada VHS","productType":"VHS Tape","tags":[]}


def test_pairing_one_time_replay_body_and_clock_rejected(rig):
    state,_,client,_,_,calls,pair=rig
    assert client.post("/v1/pair",json=pair).status_code==401
    payload={"operationId":str(uuid.uuid4()),"operation":"health","arguments":{}};body,headers=signed(rig,"/v1/execute",payload)
    assert client.post("/v1/execute",content=body,headers=headers).status_code==200
    assert client.post("/v1/execute",content=body,headers=headers).json()["error"]["code"]=="REPLAY"
    assert client.post("/v1/execute",content=body+b" ",headers=headers).status_code==401
    body,headers=signed(rig,"/v1/execute",payload,timestamp=0)
    assert client.post("/v1/execute",content=body,headers=headers).status_code==401
    assert all(request.headers.get("x-shopify-access-token")!="fixture-server-secret" for request in calls)
    assert b"fixture-shopify-token" not in state.path.read_bytes()


def test_cached_creation_and_conflicting_retry_never_duplicate(rig):
    args=create_args();operation=str(uuid.uuid4())
    first=rpc(rig,"create",args,operation);assert first.status_code==200
    assert rpc(rig,"create",args,operation).json()==first.json()
    assert sum(b"SnapIMSCreate" in r.content for r in rig[5])==1
    changed=dict(args,title="Changed")
    assert rpc(rig,"create",changed,operation).json()["error"]["code"]=="OPERATION_CONFLICT"
    assert sum(b"SnapIMSCreate" in r.content for r in rig[5])==1
    assert b"Synthetic fixture" not in rig[0].path.read_bytes()


def test_uncertain_create_only_reconciles_and_never_resends(rig):
    service=rig[1];original=service.http;attempts=[]
    def fail(request):
        if b"SnapIMSCreate" in request.content:
            attempts.append(request)
            raise httpx.ReadTimeout("mock transport only")
        return original.send(request)
    service.http=httpx.Client(transport=httpx.MockTransport(fail));args=create_args();identity=str(uuid.uuid4())
    assert rpc(rig,"create",args,identity).json()["error"]["code"]=="UNCERTAIN"
    assert rpc(rig,"create",args,identity).json()["error"]["code"]=="UNCERTAIN"
    assert len(attempts)==1


def test_unknown_destructive_publish_and_foreign_resource_denied(rig):
    assert rpc(rig,"delete_product",{}).status_code==403
    assert rpc(rig,"publish",{"creationKey":str(uuid.uuid4()),"productId":"gid://shopify/Product/9","publicationId":"gid://shopify/Publication/9"}).json()["error"]["code"]=="PUBLISH_DISABLED"
    response=rpc(rig,"variant",{"creationKey":str(uuid.uuid4()),"productId":"gid://shopify/Product/9","variantId":"gid://shopify/ProductVariant/9","priceCents":1299,"barcode":""})
    assert response.json()["error"]["code"]=="UNOWNED_RESOURCE"


def test_quantity_requires_compare_and_set_and_idempotency():
    values={"creationKey":str(uuid.uuid4()),"inventoryItemId":"gid://shopify/InventoryItem/3","locationId":"gid://shopify/Location/1","idempotencyKey":str(uuid.uuid4()),"quantity":1,"changeFromQuantity":0}
    op=prepare("stock_set",values)
    assert "@idempotent" in op.query
    assert op.variables["input"]["quantities"][0]["changeFromQuantity"]==0
    with pytest.raises(BrokerError):prepare("stock_set",{k:v for k,v in values.items() if k!="changeFromQuantity"})
    with pytest.raises(BrokerError):prepare("stock_set",dict(values,quantity=2))


def test_oauth_signature_state_and_token_scope_remain_server_side(rig):
    state,old,client,key,device,calls,_=rig
    config=Config(old.config.public_url,old.config.shop,old.config.client_id,old.config.client_secret)
    service=Service(config,state,old.http);api=TestClient(create_app(service),raise_server_exceptions=False)
    body,headers=signed(rig,"/v1/authorize",{})
    start=api.post("/v1/authorize",content=body,headers=headers).json();params=parse_qs(urlsplit(start["url"]).query)
    assert "client_secret" not in start["url"]
    callback={"shop":config.shop,"timestamp":str(int(time.time())),"code":"fixture-auth-code","state":params["state"][0]}
    callback["hmac"]=hmac.new(config.client_secret.encode(),urlencode(sorted(callback.items())).encode(),hashlib.sha256).hexdigest()
    response=api.get("/oauth/callback",params=callback)
    assert response.status_code==200 and "fixture-shopify-token" not in response.text
    assert api.get("/oauth/callback",params=callback).status_code==401
    assert "fixture-shopify-token"==state.get("shopify-token")["accessToken"]


def test_revocation_stops_previously_paired_device(rig):
    body,headers=signed(rig,"/v1/revoke",{})
    assert rig[2].post("/v1/revoke",content=body,headers=headers).json()["revoked"]
    assert rpc(rig,"health",{}).status_code==401


def test_production_guard_and_currency_guard_stop_mutations(rig):
    service=rig[1];original=service.health
    for change,code in [({"developmentStore":False},"DEVELOPMENT_ONLY"),({"currency":"USD"},"CURRENCY_MISMATCH"),({"scopes":[]},"MISSING_SCOPES")]:
        service.health=lambda *a,**k:dict(original(),**change)
        assert rpc(rig,"create",create_args()).json()["error"]["code"]==code
    assert not any(b"SnapIMSCreate" in r.content for r in rig[5])


def test_token_renewal_rotates_encrypted_credentials_and_rejected_token(rig):
    service=rig[1];state=rig[0]
    service.token();saved=state.get("shopify-token");saved["expiresAt"]=0;state.put("shopify-token",saved)
    values=iter(["fixture-token-two","fixture-token-three"])
    service._token_request=lambda payload:{"access_token":next(values),"expires_in":86399}
    assert service.token()=="fixture-token-two"
    assert service.token(rejected="fixture-token-two")=="fixture-token-three"
    assert state.get("shopify-token")["accessToken"]=="fixture-token-three"
    assert b"fixture-token-three" not in state.path.read_bytes()


def test_uncertain_file_can_reconcile_after_phone_upload_credentials_are_lost(rig):
    args=create_args();assert rpc(rig,"create",args).status_code==200
    service=rig[1];original=service.http;attempts=[];pending=str(uuid.uuid4())
    file={"creationKey":args["creationKey"],"source":"https://storage.googleapis.com/fixture/staged","filename":args["itemId"]+"-F.jpg","alt":"snapims:"+args["itemId"]+":photo:fixture"}
    def transport(request):
        if b"SnapIMSFile(" in request.content:
            attempts.append(request);raise httpx.ReadTimeout("synthetic after acceptance")
        if b"SnapIMSFiles(" in request.content:
            return httpx.Response(200,json={"data":{"files":{"nodes":[{"id":"gid://shopify/MediaImage/4","alt":file["alt"],"fileStatus":"READY"}],"pageInfo":{"hasNextPage":False}}}})
        return original.send(request)
    service.http=httpx.Client(transport=httpx.MockTransport(transport))
    assert rpc(rig,"file_create",file,pending).json()["error"]["code"]=="UNCERTAIN"
    recover={k:file[k] for k in ("creationKey","filename","alt")};recover["pendingOperationId"]=pending
    result=rpc(rig,"recover_file",recover);assert result.status_code==200
    assert result.json()["result"]["files"][0]["id"]=="gid://shopify/MediaImage/4"
    assert rpc(rig,"recover_file",recover).json()==result.json();assert len(attempts)==1
    assert rpc(rig,"recover_file",dict(recover,alt="another copy")).status_code==409


def test_preflight_read_failure_can_retry_without_poisoning_creation(rig):
    service=rig[1];original=service.http;failed=[False]
    def transport(request):
        if b"SnapIMSFind" in request.content and not failed[0]:
            failed[0]=True;raise httpx.ReadTimeout("synthetic before mutation")
        return original.send(request)
    service.http=httpx.Client(transport=httpx.MockTransport(transport));args=create_args();op=str(uuid.uuid4())
    assert rpc(rig,"create",args,op).status_code==502
    assert rpc(rig,"create",args,op).status_code==200
    assert sum(b"SnapIMSCreate" in r.content for r in rig[5])==1


def test_expired_pairing_and_invalid_device_and_upload_hosts(rig):
    state,_,client,_,_,_,pair=rig
    assert client.post("/v1/pair",json=dict(pair,code=state.pairing_code(-1),deviceId=str(uuid.uuid4()))).status_code==401
    assert client.post("/v1/pair",json=dict(pair,code=state.pairing_code(),deviceId="invalid")).status_code==400
    from broker.operations import upload_url
    for value in ["http://storage.googleapis.com/file","https://storage.googleapis.com.evil.invalid/file","https://user@storage.googleapis.com/file","https://127.0.0.1/file"]:
        with pytest.raises(BrokerError):upload_url(value)
