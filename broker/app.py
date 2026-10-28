"""Run behind HTTPS ingress with query-string access logging disabled."""
from __future__ import annotations
import json
import os
from pathlib import Path
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse,PlainTextResponse
from starlette.concurrency import run_in_threadpool
from .security import State,BrokerError,unb64
from .service import Config,Service


def create_app(service:Service|None=None):
    if service is None:
        service=Service(Config.environment(),State(Path(os.environ.get("BROKER_STATE_PATH","/data/broker.sqlite")),unb64(os.environ["BROKER_MASTER_KEY"])))
    app=FastAPI(docs_url=None,redoc_url=None,openapi_url=None)
    @app.exception_handler(BrokerError)
    async def failed(request:Request,error:BrokerError):
        headers={"Cache-Control":"no-store"}
        if error.retry_after:headers["Retry-After"]=str(error.retry_after)
        return JSONResponse({"error":{"code":error.code,"message":error.message,"retryAfter":error.retry_after}},status_code=error.status,headers=headers)
    @app.exception_handler(Exception)
    async def unavailable(request:Request,error:Exception):
        return JSONResponse({"error":{"code":"BROKER_UNAVAILABLE","message":"Broker could not complete the request. Review the saved operation and server configuration; no blind retry of creation.","retryAfter":0}},status_code=503,headers={"Cache-Control":"no-store"})
    @app.get("/healthz")
    def healthz():
        return {"service":"SnapIMS Shopify credential broker","version":1}
    async def body(request:Request):
        data=bytearray()
        async for chunk in request.stream():
            data.extend(chunk)
            if len(data)>1_000_000:raise BrokerError("BODY_TOO_LARGE","Broker request exceeds its metadata limit.",413)
        try:
            def unique(pairs):
                result={}
                for key,value in pairs:
                    if key in result:raise ValueError()
                    result[key]=value
                return result
            value=json.loads(data,object_pairs_hook=unique,parse_constant=lambda _:(_ for _ in ()).throw(ValueError()))
            if not isinstance(value,dict):raise ValueError()
        except (ValueError,UnicodeError,RecursionError):
            raise BrokerError("BAD_JSON","Invalid broker request JSON.") from None
        return bytes(data),value
    @app.post("/v1/pair")
    async def pair(request:Request):
        raw,value=await body(request)
        def work():
            service.state.rate("pair:"+(request.client.host if request.client else "unknown"),20)
            if set(value)!={"code","deviceId","publicKey"} or any(not isinstance(v,str) or len(v)>2000 for v in value.values()):
                raise BrokerError("PAIRING_INPUT","Pairing code, device ID and public key are required.")
            service.state.pair(value["code"],value["deviceId"],value["publicKey"])
            service.state.audit(value["deviceId"],"pair","PAIRED","")
            return {"paired":True,"shop":service.config.shop,"apiVersion":"2026-10"}
        return JSONResponse(await run_in_threadpool(work),headers={"Cache-Control":"no-store"})
    @app.post("/v1/execute")
    async def execute(request:Request):
        raw,value=await body(request)
        def work():
            service.state.rate("rpc:"+(request.client.host if request.client else "unknown"),240)
            device=service.state.authenticate(service.config.public_url,"/v1/execute",raw,dict(request.headers))
            if set(value)!={"operationId","operation","arguments"} or not isinstance(value["operation"],str) or len(value["operation"])>60:
                raise BrokerError("INVALID_INPUT","A typed operation and immutable operation ID are required.")
            return {"result":service.execute(device,value["operationId"],value["operation"],value["arguments"])}
        return JSONResponse(await run_in_threadpool(work),headers={"Cache-Control":"no-store"})
    @app.post("/v1/authorize")
    async def authorize(request:Request):
        raw,value=await body(request)
        def work():
            device=service.state.authenticate(service.config.public_url,"/v1/authorize",raw,dict(request.headers))
            if value:raise BrokerError("INVALID_INPUT","Authorization does not accept arbitrary redirects.")
            return service.auth_start(device)
        return JSONResponse(await run_in_threadpool(work),headers={"Cache-Control":"no-store"})
    @app.post("/v1/revoke")
    async def revoke(request:Request):
        raw,value=await body(request)
        def work():
            device=service.state.authenticate(service.config.public_url,"/v1/revoke",raw,dict(request.headers))
            if value:raise BrokerError("INVALID_INPUT","This endpoint revokes only the signing device.")
            with service.state.connect() as db:db.execute("UPDATE devices SET revoked=1 WHERE id=?",(device,))
            service.state.audit(device,"revoke","REVOKED","")
            return {"revoked":True}
        return JSONResponse(await run_in_threadpool(work),headers={"Cache-Control":"no-store"})
    @app.get("/oauth/callback")
    async def callback(request:Request):
        await run_in_threadpool(service.auth_callback,list(request.query_params.multi_items()))
        return PlainTextResponse("Shopify authorization received. Return to SnapIMS and test the connection.",headers={"Cache-Control":"no-store","Referrer-Policy":"no-referrer"})
    return app
