from broker.operations import prepare
from graphql import parse
key='11111111-1111-4111-8111-111111111111'
owned={'creationKey':key}
p='gid://shopify/Product/1';v='gid://shopify/ProductVariant/1';it='gid://shopify/InventoryItem/1';l='gid://shopify/Location/1';f='gid://shopify/MediaImage/1';pub='gid://shopify/Publication/1'
fixtures={
'health':{},'find':{'itemId':'fixture-A1-001',**owned},'product':{'productId':p,'locationId':l},'quantity':{'inventoryItemId':it,'locationId':l},'stage':{'filename':'fixture-F.jpg','bytes':100},
'create':dict(owned,itemId='fixture-A1-001',title='Fixture',descriptionHtml='<p>Fixture</p>',vendor='Canada VHS',productType='VHS',tags=[]),
'variant':dict(owned,productId=p,variantId=v,priceCents=1299,barcode=''),'inventory_item':dict(owned,inventoryItemId=it,itemId='fixture-A1-001',weightGrams=250),
'stock_activate':dict(owned,inventoryItemId=it,locationId=l,idempotencyKey=key,quantity=1),'stock_set':dict(owned,inventoryItemId=it,locationId=l,idempotencyKey=key,quantity=1,changeFromQuantity=0),
'file_create':dict(owned,source='https://storage.googleapis.com/fixture',filename='fixture-F.jpg',alt='fixture'),'files_find':{'filename':'fixture-F.jpg'},'file_status':{'fileId':f},
'attach':dict(owned,productId=p,fileId=f),'reorder':dict(owned,productId=p,fileIds=[f]),'remote_job':{'jobId':'gid://shopify/Job/1'},'activate_product':dict(owned,productId=p),
'publish':dict(owned,productId=p,publicationId=pub),'publication_status':dict(owned,productId=p,publicationId=pub)}
fixtures.update(browse={'query':'Fixture','status':'DRAFT','cursor':''},claim=dict(owned,itemId='fixture-A1-001',productId=p,locationId=l,expectedVersion='a'*64),metadata_update=dict(owned,itemId='fixture-A1-001',productId=p,locationId=l,expectedVersion='a'*64,patch={'title':'Reviewed'}))
def test_closed_registry_graphql_documents_have_valid_syntax():
    # Syntax only. Live Shopify schema compatibility remains unverified.
    assert len(fixtures)==22
    for kind,args in fixtures.items():
        parse(prepare(kind,args,True).query)
