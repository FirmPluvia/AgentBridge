"""Trusted relay. TLS terminates at Caddy; payloads are visible to this server.
No operation queue and no reconnect replay. Each websocket belongs to one device.
"""
import asyncio
import hashlib
import json
import os
import secrets
import sqlite3
import time
import uuid
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from pathlib import Path
from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field


def digest(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def create_app(db_path: str | None = None, *, trust_proxy: bool | None = None) -> FastAPI:
    db_path = db_path or os.getenv("AB_DB", "relay-data/relay.sqlite3")
    if db_path != ":memory:":
        Path(db_path).parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(db_path, check_same_thread=False)
    db.row_factory = sqlite3.Row
    db.executescript("""
    PRAGMA journal_mode=WAL;
    CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY, token TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS controllers(id TEXT PRIMARY KEY, device TEXT NOT NULL,
       token TEXT UNIQUE NOT NULL, name TEXT NOT NULL, revoked INTEGER DEFAULT 0);
    """)
    db.commit()
    channels: dict[str, WebSocket] = {}
    pending: dict[str, tuple[str, str, asyncio.Future]] = {}
    pairings: dict[str, dict] = {}
    rates: dict[str, deque] = defaultdict(deque)
    trust_proxy = trust_proxy if trust_proxy is not None else os.getenv("AB_TRUST_PROXY") == "1"

    @asynccontextmanager
    async def lifespan(app):
        yield
        for ws in list(channels.values()):
            await ws.close(code=1001)
        db.close()

    app = FastAPI(title="AgentBridge Relay", version="1.0.0", lifespan=lifespan)
    app.state.db = db
    app.state.channels = channels

    def rate(key: str, limit=120):
        now = time.monotonic()
        q = rates[key]
        while q and q[0] < now - 60:
            q.popleft()
        if len(q) >= limit:
            raise HTTPException(429, "RATE_LIMITED")
        q.append(now)
        if len(rates) > 10000:
            for k in list(rates):
                if not rates[k] or rates[k][-1] < now - 60:
                    rates.pop(k, None)

    def token(req) -> str:
        value = req.headers.get("authorization", "")
        if not value.startswith("Bearer "):
            raise HTTPException(401, "UNAUTHORIZED")
        return value[7:]

    def device_auth(req, device: str):
        row = db.execute("SELECT token FROM devices WHERE id=?", (device,)).fetchone()
        if row is None or not secrets.compare_digest(row[0], digest(token(req))):
            raise HTTPException(401, "UNAUTHORIZED")

    def controller_auth(req, device: str | None = None):
        row = db.execute("SELECT * FROM controllers WHERE token=? AND revoked=0", (digest(token(req)),)).fetchone()
        if row is None or (device is not None and row["device"] != device):
            raise HTTPException(401, "UNAUTHORIZED")
        return row

    def failure(body: dict, device: str, code: str, message: str, state="failed"):
        return dict(requestId=body.get("requestId", ""), deviceId=device, backend=None,
                    success=False, durationMs=0, state=state, data=None,
                    error=dict(code=code, message=message, retryable=code == "DEVICE_OFFLINE" and state != "unknown"))

    @app.middleware("http")
    async def protect(request: Request, call_next):
        scheme = request.url.scheme
        if trust_proxy:
            scheme = request.headers.get("x-forwarded-proto", scheme)
        if scheme != "https":
            return JSONResponse({"error": "TLS_REQUIRED"}, 400)
        if request.headers.get("transfer-encoding") or int(request.headers.get("content-length", "0")) > 65536:
            return JSONResponse({"error": "REQUEST_TOO_LARGE"}, 413)
        try:
            rate("ip:" + (request.client.host if request.client else "unknown"), 240)
        except HTTPException:
            return JSONResponse({"error": "RATE_LIMITED"}, 429)
        return await call_next(request)

    class Registration(BaseModel):
        deviceId: str = Field(min_length=16, max_length=100, pattern=r"^[a-zA-Z0-9-]+$")
        deviceToken: str = Field(min_length=40, max_length=200)

    @app.post("/devices/register")
    async def register(p: Registration, req: Request):
        row = db.execute("SELECT token FROM devices WHERE id=?", (p.deviceId,)).fetchone()
        if row:
            device_auth(req, p.deviceId)
            if not secrets.compare_digest(row[0], digest(p.deviceToken)):
                raise HTTPException(409, "DEVICE_EXISTS")
        else:
            if token(req) != p.deviceToken:
                raise HTTPException(401, "UNAUTHORIZED")
            db.execute("INSERT INTO devices VALUES(?,?)", (p.deviceId, digest(p.deviceToken)))
            db.commit()
        return {"deviceId": p.deviceId}

    class PairRequest(BaseModel):
        deviceId: str = Field(max_length=100)
        name: str = Field(min_length=1, max_length=80)

    @app.post("/pairings")
    async def pairing(p: PairRequest, req: Request):
        rate("pair:" + p.deviceId, 5)
        ws = channels.get(p.deviceId)
        if ws is None:
            raise HTTPException(409, "DEVICE_OFFLINE")
        now = time.time()
        for old in list(pairings):
            if pairings[old]["expires"] < now:
                pairings.pop(old, None)
        pid, secret, controller = str(uuid.uuid4()), secrets.token_urlsafe(32), str(uuid.uuid4())
        code = f"{secrets.randbelow(1000000):06d}"
        pairings[pid] = dict(device=p.deviceId, name=p.name, controller=controller,
                             poll=digest(secret), expires=now + 180, approved=None, token=None)
        await ws.send_json(dict(type="pairing", pairingId=pid, controllerId=controller, name=p.name, code=code))
        return dict(pairingId=pid, pollSecret=secret, code=code, expiresIn=180)

    @app.get("/pairings/{pid}")
    async def poll(pid: str, req: Request):
        p = pairings.get(pid)
        if p is None or p["expires"] < time.time():
            raise HTTPException(404, "PAIRING_EXPIRED")
        if not secrets.compare_digest(digest(token(req)), p["poll"]):
            raise HTTPException(401, "UNAUTHORIZED")
        result = dict(approved=p["approved"], controllerId=p["controller"], controllerToken=p["token"])
        if p["approved"] is not None:
            pairings.pop(pid)
        return result

    class Decision(BaseModel):
        approved: bool

    @app.post("/devices/{device}/pairings/{pid}/decision")
    async def decide(device: str, pid: str, data: Decision, req: Request):
        device_auth(req, device)
        p = pairings.get(pid)
        if p is None or p["device"] != device or p["expires"] < time.time() or p["approved"] is not None:
            raise HTTPException(404, "PAIRING_EXPIRED")
        p["approved"] = data.approved
        if data.approved:
            credential = secrets.token_urlsafe(32)
            db.execute("INSERT INTO controllers(id,device,token,name) VALUES(?,?,?,?)",
                       (p["controller"], device, digest(credential), p["name"]))
            db.commit()
            p["token"] = credential
        return {"approved": data.approved}

    @app.post("/devices/{device}/revoke")
    async def revoke(device: str, req: Request):
        device_auth(req, device)
        db.execute("UPDATE controllers SET revoked=1 WHERE device=?", (device,)); db.commit()
        for did, cid, fut in list(pending.values()):
            if did == device and not fut.done():
                fut.set_result(failure({}, device, "CANCELLED", "Controller revoked; execution may already have occurred", "unknown"))
        return {"revoked": True}

    @app.post("/devices/{device}/controllers/{controller}/revoke")
    async def revoke_one(device: str, controller: str, req: Request):
        device_auth(req, device)
        db.execute("UPDATE controllers SET revoked=1 WHERE device=? AND id=?", (device, controller)); db.commit()
        return {"revoked": True}

    class Rotation(BaseModel):
        deviceToken: str = Field(min_length=40, max_length=200)

    @app.post("/devices/{device}/rotate")
    async def rotate(device: str, p: Rotation, req: Request):
        device_auth(req, device)
        db.execute("UPDATE devices SET token=? WHERE id=?", (digest(p.deviceToken), device)); db.commit()
        if device in channels:
            await channels[device].close(code=1000)
        return {"rotated": True}

    @app.get("/devices")
    async def devices(req: Request):
        c = controller_auth(req)
        return {"devices": [{"deviceId": c["device"], "online": c["device"] in channels}]}

    @app.websocket("/device/ws")
    async def device_ws(ws: WebSocket, deviceId: str):
        scheme = ws.url.scheme
        if trust_proxy:
            scheme = ws.headers.get("x-forwarded-proto", scheme)
        if scheme not in ("wss", "https"):
            await ws.close(code=1008); return
        try:
            device_auth(ws, deviceId)
        except HTTPException:
            await ws.close(code=1008); return
        if deviceId in channels:
            await ws.close(code=1008); return
        await ws.accept()
        channels[deviceId] = ws
        try:
            while True:
                try:
                    packet = await asyncio.wait_for(ws.receive_json(), 45)
                except asyncio.TimeoutError:
                    await ws.send_json({"type": "ping"})
                    packet = await asyncio.wait_for(ws.receive_json(), 15)
                if packet.get("type") == "result":
                    route = packet.get("routeId")
                    item = pending.get(route)
                    if item is not None and item[0] == deviceId and not item[2].done():
                        item[2].set_result(packet["result"])
        except (WebSocketDisconnect, RuntimeError, asyncio.TimeoutError, ValueError):
            pass
        finally:
            if channels.get(deviceId) is ws:
                channels.pop(deviceId, None)
            for did, cid, fut in list(pending.values()):
                if did == deviceId and not fut.done():
                    fut.set_result(failure({}, did, "DEVICE_OFFLINE", "Device disconnected; side effect result unknown", "unknown"))

    allowed = {"status", "capabilities", "screenshot", "ui-tree", "tap", "long-press", "swipe", "text", "key", "apps", "launch", "foreground", "locate", "shell"}

    async def dispatch(device: str, op: str, body: dict, req):
        c = controller_auth(req, device)
        rate("controller:" + c["id"])
        parts = op.split("/")
        valid_nested = ((len(parts) == 2 and parts[0] in ("jobs", "requests")) or
                        (len(parts) == 3 and parts[0] in ("jobs", "sessions") and parts[2] in ("cancel", "stop")))
        if op not in allowed and not valid_nested:
            raise HTTPException(404, "UNSUPPORTED")
        if not isinstance(body.get("requestId"), str) or len(body["requestId"]) > 128 or body.get("deviceId") != device:
            raise HTTPException(400, "requestId and matching deviceId required")
        deadline = body.get("deadline", 0)
        now = time.time() * 1000
        if not isinstance(deadline, (int, float)) or not now < deadline <= now + 120000:
            return failure(body, device, "TIMEOUT", "Invalid or expired deadline")
        ws = channels.get(device)
        if ws is None:
            return failure(body, device, "DEVICE_OFFLINE", "Device offline; request was not sent")
        if len(pending) >= 256:
            raise HTTPException(429, "Too many pending requests")
        route = str(uuid.uuid4())
        future = asyncio.get_running_loop().create_future()
        pending[route] = (device, c["id"], future)
        try:
            await ws.send_json(dict(type="request", routeId=route, controllerId=c["id"], path="/api/v1/" + op, body=body))
            result = await asyncio.wait_for(future, max(0.001, (deadline - time.time()*1000) / 1000))
            if not result.get("requestId"):
                result["requestId"] = body["requestId"]
            result.setdefault("deviceId", device)
            return result
        except (asyncio.TimeoutError, RuntimeError, WebSocketDisconnect):
            return failure(body, device, "TIMEOUT", "Delivery or execution result unknown; never automatically replay", "unknown")
        finally:
            pending.pop(route, None)

    @app.api_route("/devices/{device}/api/v1/{op:path}", methods=["GET", "POST"])
    async def control(device: str, op: str, req: Request):
        expected = "GET" if op in ("status", "capabilities", "apps", "foreground") or op.startswith("requests/") or (op.startswith("jobs/") and not op.endswith("/cancel")) else "POST"
        if req.method != expected:
            raise HTTPException(405, "Invalid method")
        body = dict(req.query_params) if req.method == "GET" else await req.json()
        if req.method == "GET" and "deadline" in body:
            body["deadline"] = int(body["deadline"])
        return await dispatch(device, op, body, req)

    @app.websocket("/controllers/ws")
    async def controller_ws(ws: WebSocket):
        scheme = ws.headers.get("x-forwarded-proto", ws.url.scheme) if trust_proxy else ws.url.scheme
        try:
            if scheme not in ("wss", "https"):
                raise HTTPException(400)
            controller_auth(ws)
        except HTTPException:
            await ws.close(code=1008); return
        await ws.accept()
        try:
            while True:
                p = await ws.receive_json()
                if len(json.dumps(p)) > 65536:
                    await ws.close(code=1009); break
                try:
                    result = await dispatch(p["body"]["deviceId"], p["path"].removeprefix("/api/v1/"), p["body"], ws)
                except HTTPException as exc:
                    result = {"error": {"code": "UNAUTHORIZED" if exc.status_code == 401 else "INVALID_ARGUMENT"}}
                await ws.send_json(result)
        except WebSocketDisconnect:
            pass

    return app

app = create_app()
