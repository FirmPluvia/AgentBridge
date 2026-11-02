"""Live isolated-container smoke check; simulates execution device, never a real click.
Only run against a disposable local relay. X-Forwarded-Proto simulates Caddy's
private upstream connection, not a public HTTP deployment.
"""
import json
import secrets
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
import httpx
from websockets.sync.client import connect


def main():
    device = str(uuid.uuid4())
    credential = secrets.token_urlsafe(32)
    device_headers = {"Authorization": "Bearer " + credential, "X-Forwarded-Proto": "https"}
    with httpx.Client(base_url="http://127.0.0.1:18080", trust_env=False) as http:
        assert http.get("/devices").status_code == 400
        assert http.get("/devices", headers={"X-Forwarded-Proto": "https"}).status_code == 401
        r = http.post("/devices/register", headers=device_headers, json={"deviceId": device, "deviceToken": credential})
        assert r.status_code == 200, r.status_code
        with connect(f"ws://127.0.0.1:18080/device/ws?deviceId={device}", additional_headers=device_headers) as ws:
            p = http.post("/pairings", headers={"X-Forwarded-Proto": "https"}, json={"deviceId": device, "name": "Disposable smoke test"}).json()
            received = json.loads(ws.recv(timeout=3))
            assert received["code"] == p["code"]
            assert http.post(f"/devices/{device}/pairings/{p['pairingId']}/decision", headers=device_headers, json={"approved": True}).status_code == 200
            controller = http.get("/pairings/" + p["pairingId"], headers={"Authorization": "Bearer " + p["pollSecret"], "X-Forwarded-Proto": "https"}).json()["controllerToken"]
            controller_headers = {"Authorization": "Bearer " + controller, "X-Forwarded-Proto": "https"}
            body = dict(requestId=str(uuid.uuid4()), deviceId=device, sessionId="smoke", deadline=int(time.time()*1000)+4000)
            with ThreadPoolExecutor(1) as pool:
                future = pool.submit(lambda: http.post(f"/devices/{device}/api/v1/status", headers=controller_headers, json=body))
                # Status must be GET: verify method enforcement before sending a valid request.
                assert future.result().status_code == 405
                future = pool.submit(lambda: http.get(f"/devices/{device}/api/v1/status", headers=controller_headers, params=body))
                route = json.loads(ws.recv(timeout=3))
                ws.send(json.dumps({"type": "result", "routeId": route["routeId"], "result": {"requestId": body["requestId"], "deviceId": device, "backend": "simulated", "success": True, "durationMs": 1, "state": "completed", "data": {"smoke": True}, "error": None}}))
                assert future.result().json()["data"] == {"smoke": True}
            assert http.post(f"/devices/{device}/revoke", headers=device_headers, json={}).status_code == 200
            assert http.get("/devices", headers=controller_headers).status_code == 401
    print("PASS: live Uvicorn + Docker HTTP/WS, TLS-required policy, separate identities, local pairing decision, route/result, method validation and revocation. Device execution was simulated; no Android hardware tested.")

if __name__ == "__main__":
    main()
