"""Typed mock protocol/ledger checks. No real Shopify authorization is claimed."""
import copy
import json
import uuid
import httpx
import pytest
from graphql import parse
from test_broker import rig,rpc,create_args
from broker.operations import prepare,product_version
from broker.security import BrokerError


@pytest.fixture
def catalog(rig):
    args=create_args();product={"id":"gid://shopify/Product/7","title":"Owner fixture title","status":"DRAFT","descriptionHtml":"<p>Private fixture description</p>","vendor":"Canada VHS","productType":"VHS Tape","tags":["retain-this-tag"],"updatedAt":"2026-10-28T01:00:00Z","identity":{"value":args['itemId']},"metafield":{"value":args['creationKey']},"variants":{"nodes":[{"id":"gid://shopify/ProductVariant/8","sku":args['itemId'],"price":"12.99","barcode":"","inventoryItem":{"id":"gid://shopify/InventoryItem/9","tracked":True,"requiresShipping":True,"measurement":{"weight":{"value":250,"unit":"GRAMS"}},"inventoryLevel":{"quantities":[{"name":"available","quantity":1}]}}}],"pageInfo":{"hasNextPage":False}},"media":{"nodes":[{"id":"gid://shopify/MediaImage/10","alt":"private image marker","preview":{"status":"READY"}}],"pageInfo":{"hasNextPage":False}}}
    service=rig[1];original=service.http;writes=[];mode={"timeout":False}
    def handler(request):
        if request.url.path.endswith('access_token'):return original.send(request)
        payload=json.loads(request.content);query=payload['query'];variables=payload['variables']
        if "SnapIMSVerify" in query:return httpx.Response(200,json={"data":{"product":copy.deepcopy(product)}})
        if "SnapIMSFind" in query:
            nodes=[{"id":product['variants']['nodes'][0]['id'],"sku":product['variants']['nodes'][0]['sku'],"product":{"id":product['id']}}]
            if mode.get('duplicate'):nodes.append({"id":"gid://shopify/ProductVariant/99","sku":args['itemId'],"product":{"id":"gid://shopify/Product/99"}})
            return httpx.Response(200,json={"data":{"variants":{"nodes":nodes,"pageInfo":{"hasNextPage":False}},"products":{"nodes":[copy.deepcopy(product)],"pageInfo":{"hasNextPage":False}}}})
        if "SnapIMSCatalog" in query:return httpx.Response(200,json={"data":{"products":{"nodes":[copy.deepcopy(product)],"pageInfo":{"hasNextPage":True,"endCursor":"opaque-next"}}}})
        if "SnapIMSMetadata" in query:
            patch=variables['product'];writes.append(copy.deepcopy(patch));product.update({k:v for k,v in patch.items() if k!='id'});product['updatedAt']='2026-10-28T02:00:00Z'
            if mode['timeout']:raise httpx.ReadTimeout("synthetic timeout after acceptance")
            return httpx.Response(200,json={"data":{"productUpdate":{"product":{k:product[k] for k in ('id','status','updatedAt')},"userErrors":[]}}})
        return original.send(request)
    service.http=httpx.Client(transport=httpx.MockTransport(handler))
    args={k:args[k] for k in ('creationKey','itemId')};args.update(productId=product['id'],locationId='gid://shopify/Location/1',expectedVersion=product_version(product))
    return product,args,writes,mode


def test_catalog_uses_bounded_cursor_variables_and_escapes_search_syntax(rig,catalog):
    op=prepare('browse',{'query':'Alien" OR status:active','status':'DRAFT','cursor':'opaque-next'})
    parse(op.query);assert 'first:20' in op.query and op.variables['after']=='opaque-next'
    assert op.variables['query']=='title:"Alien\\" OR status:active" AND status:draft'
    response=rpc(rig,'browse',{'query':'Alien','status':'','cursor':''});assert response.status_code==200
    assert response.json()['result']['pageInfo']['endCursor']=='opaque-next'
    for args in ({'query':'x','status':'DELETED','cursor':''},{'query':'x','status':'','cursor':'x'*2001}):
        with pytest.raises(BrokerError):prepare('browse',args)


def test_claim_proves_exact_markers_and_sku_and_stores_only_resource_ids(rig,catalog):
    product,args,writes,_=catalog;read=rpc(rig,'product',{k:args[k] for k in ('productId','locationId')}).json()['result'];assert read['reviewVersion']==args['expectedVersion']
    op=str(uuid.uuid4());first=rpc(rig,'claim',args,op);assert first.status_code==200 and not writes
    assert rpc(rig,'claim',args,op).json()==first.json()
    with rig[0].connect() as db:assert db.execute('SELECT COUNT(*) FROM resources').fetchone()[0]==3
    assert b'Owner fixture title' not in rig[0].path.read_bytes();assert b'Private fixture description' not in rig[0].path.read_bytes()


def test_claim_rejects_foreign_ambiguous_live_or_changed_products(rig,catalog):
    product,args,writes,_=catalog
    cases=[('status','ACTIVE'),('status','ARCHIVED'),('metafield',{'value':str(uuid.uuid4())}),('identity',{'value':'another-copy'})]
    for key,value in cases:
        old=product[key];product[key]=value
        assert rpc(rig,'claim',args).json()['error']['code']=='IDENTITY_REVIEW';product[key]=old
    product['variants']['pageInfo']['hasNextPage']=True
    assert rpc(rig,'claim',args).status_code==409;product['variants']['pageInfo']['hasNextPage']=False
    product['title']='Changed remotely';assert rpc(rig,'claim',args).json()['error']['code']=='REMOTE_CHANGED';assert not writes
    with rig[0].connect() as db:assert db.execute('SELECT COUNT(*) FROM resources').fetchone()[0]==0


def test_metadata_update_is_exact_allowlist_and_does_not_repeat_completed_write(rig,catalog):
    product,args,writes,_=catalog;assert rpc(rig,'claim',args).status_code==200
    protected=copy.deepcopy({k:product[k] for k in ('status','tags','variants','media','vendor','productType')})
    update=dict(args,patch={'title':'Reviewed title','descriptionHtml':'<p>Reviewed description</p>'});op=str(uuid.uuid4())
    assert rpc(rig,'metadata_update',update,op).status_code==200;assert rpc(rig,'metadata_update',update,op).status_code==200
    assert writes==[{'id':args['productId'],**update['patch']}]
    assert {k:product[k] for k in protected}==protected
    assert b'Reviewed title' not in rig[0].path.read_bytes()
    assert rpc(rig,'metadata_update',dict(update,patch={'title':'different'}),op).json()['error']['code']=='OPERATION_CONFLICT'
    for patch in ({'status':'ACTIVE'},{'tags':[]},{'variants':[]},{'price':'0'},{'title':''},{}):
        with pytest.raises(BrokerError):prepare('metadata_update',dict(args,patch=patch))


def test_metadata_remote_change_or_unowned_product_prevents_dispatch(rig,catalog):
    product,args,writes,_=catalog
    assert rpc(rig,'metadata_update',dict(args,patch={'title':'Reviewed'})).json()['error']['code']=='UNOWNED_RESOURCE'
    assert rpc(rig,'claim',args).status_code==200;product['vendor']='Changed in admin'
    assert rpc(rig,'metadata_update',dict(args,patch={'title':'Reviewed'})).json()['error']['code']=='REMOTE_CHANGED';assert not writes


def test_accepted_but_disconnected_metadata_is_read_reconciled_without_resend(rig,catalog):
    product,args,writes,mode=catalog;assert rpc(rig,'claim',args).status_code==200
    mode['timeout']=True;update=dict(args,patch={'title':'Reviewed'});op=str(uuid.uuid4())
    assert rpc(rig,'metadata_update',update,op).json()['error']['code']=='UNCERTAIN'
    recovered=rpc(rig,'metadata_update',update,op);assert recovered.status_code==200 and recovered.json()['result']['reconciled'];assert len(writes)==1


def test_uncertain_metadata_with_other_remote_edits_requires_manual_review(rig,catalog):
    product,args,writes,mode=catalog;assert rpc(rig,'claim',args).status_code==200
    mode['timeout']=True;update=dict(args,patch={'title':'Reviewed'});op=str(uuid.uuid4());assert rpc(rig,'metadata_update',update,op).status_code==409
    product['variants']['nodes'][0]['price']='99.99';assert rpc(rig,'metadata_update',update,op).json()['error']['code']=='UNCERTAIN';assert len(writes)==1


def test_ambiguous_duplicate_sku_cannot_be_adopted(rig,catalog):
    _,args,writes,mode=catalog;mode['duplicate']=True
    assert rpc(rig,'claim',args).json()['error']['code']=='DUPLICATE_REVIEW';assert not writes
