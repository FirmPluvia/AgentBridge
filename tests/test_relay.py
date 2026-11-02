import secrets
import time
import uuid
from fastapi.testclient import TestClient
from relay.app import create_app

D="11111111-1111-1111-1111-111111111111"
E="22222222-2222-2222-2222-222222222222"
T=secrets.token_urlsafe(32)
def headers(t):return {"Authorization":"Bearer "+t}
def register(c,d=D,t=T):
    assert c.post("/devices/register",json={"deviceId":d,"deviceToken":t},headers=headers(t)).status_code==200

def pair(c,ws,d=D):
    p=c.post("/pairings",json={"deviceId":d,"name":"test"}).json()
    msg=ws.receive_json();assert msg["type"]=="pairing" and msg["code"]==p["code"]
    assert c.get("/pairings/"+p["pairingId"],headers=headers(p["pollSecret"])).json()["approved"] is None
    assert c.post(f"/devices/{d}/pairings/{p['pairingId']}/decision",json={"approved":True},headers=headers(T)).status_code==200
    return c.get("/pairings/"+p["pairingId"],headers=headers(p["pollSecret"])).json()["controllerToken"]

def test_auth_tls_pairing_revoke_isolation():
    with TestClient(create_app(":memory:"),base_url="https://testserver") as c:
        register(c);register(c,E)
        assert c.post("/devices/register",json={"deviceId":D,"deviceToken":secrets.token_urlsafe(32)},headers=headers("wrong")).status_code==401
        assert c.get("/devices",headers=headers(T)).status_code==401
        with c.websocket_connect(f"wss://testserver/device/ws?deviceId={D}",headers=headers(T)) as ws:
            token=pair(c,ws)
            assert c.get("/devices",headers=headers(token)).json()["devices"][0]["online"]
            assert c.post(f"/devices/{E}/api/v1/tap",json={},headers=headers(token)).status_code==401
            expired={"requestId":"r","deviceId":D,"deadline":1}
            assert c.post(f"/devices/{D}/api/v1/tap",json=expired,headers=headers(token)).json()["error"]["code"]=="TIMEOUT"
            c.post(f"/devices/{D}/revoke",json={},headers=headers(T))
            assert c.get("/devices",headers=headers(token)).status_code==401
        assert c.get("http://testserver/devices",headers=headers(token)).status_code==400

def test_disconnect_no_replay():
    from concurrent.futures import ThreadPoolExecutor
    with TestClient(create_app(":memory:"),base_url="https://testserver") as c:
        register(c)
        with c.websocket_connect(f"wss://testserver/device/ws?deviceId={D}",headers=headers(T)) as ws:
            token=pair(c,ws)
            pool=ThreadPoolExecutor(1)
            body={"requestId":str(uuid.uuid4()),"deviceId":D,"sessionId":"s","deadline":int(time.time()*1000)+3000,"x":1,"y":2,"imageId":"i"}
            future=pool.submit(lambda:c.post(f"/devices/{D}/api/v1/tap",json=body,headers=headers(token)))
            msg=ws.receive_json();assert msg["body"]["requestId"]==body["requestId"]
            ws.close()
        result=future.result(5).json();pool.shutdown()
        assert result["state"]=="unknown"
        assert result["error"]["retryable"] is False
        assert result["requestId"]==body["requestId"]
        with c.websocket_connect(f"wss://testserver/device/ws?deviceId={D}",headers=headers(T)) as ws:
            # No pending operations are queued on reconnect; first message must be new pairing.
            c.post("/pairings",json={"deviceId":D,"name":"new"})
            assert ws.receive_json()["type"]=="pairing"

def test_rotation_and_denied_pairing():
    with TestClient(create_app(":memory:"),base_url="https://testserver") as c:
        register(c)
        with c.websocket_connect(f"wss://testserver/device/ws?deviceId={D}",headers=headers(T)) as ws:
            p=c.post("/pairings",json={"deviceId":D,"name":"deny"}).json();ws.receive_json()
            assert c.post(f"/devices/{E}/pairings/{p['pairingId']}/decision",json={"approved":True},headers=headers(T)).status_code==401
            c.post(f"/devices/{D}/pairings/{p['pairingId']}/decision",json={"approved":False},headers=headers(T))
            assert c.get("/pairings/"+p["pairingId"],headers=headers(p["pollSecret"])).json()["approved"] is False
        next_token=secrets.token_urlsafe(32)
        c.post(f"/devices/{D}/rotate",json={"deviceToken":next_token},headers=headers(T))
        assert c.post(f"/devices/{D}/revoke",json={},headers=headers(T)).status_code==401
        assert c.post(f"/devices/{D}/revoke",json={},headers=headers(next_token)).status_code==200
