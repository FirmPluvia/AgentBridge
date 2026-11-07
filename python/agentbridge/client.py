from __future__ import annotations
import asyncio
import base64
import os
import ssl
import time
import uuid
from pathlib import Path
from typing import Any
import httpx

class BridgeException(Exception):
    def __init__(self, code: str, message: str, result: dict | None = None):
        super().__init__(f"{code}: {message}")
        self.code, self.result = code, result

class AgentBridge:
    """Identical operations for direct HTTPS and trusted relay HTTPS.

    A request is sent once. Transport failure means delivery/execution may be unknown.
    Coordinates use the actual pixels of a screenshot less than 30 seconds old.
    """
    def __init__(self, url: str, token: str, device_id: str, *, relay=False,
                 ca: str | None = None, ca_pem: str | None = None, allow_http=False, session_id: str | None = None,
                 http_client: httpx.AsyncClient | None = None):
        if not url.startswith("https://") and not (allow_http and not relay and url.startswith("http://")):
            raise ValueError("HTTPS required; direct trusted HTTP needs explicit allow_http=True")
        self.url, self.token, self.device_id = url.rstrip("/"), token, device_id
        self.relay = relay
        self.session_id = session_id or str(uuid.uuid4())
        self.last_image: dict | None = None
        if ca and ca_pem:
            raise ValueError("Choose a CA file or inline certificate, not both")
        context = ssl.create_default_context(cadata=ca_pem) if ca_pem else ssl.create_default_context(cafile=ca) if ca else ssl.create_default_context()
        self._owns_client = http_client is None
        self.http = http_client or httpx.AsyncClient(verify=context, follow_redirects=False)

    @classmethod
    def from_connection_config(cls, config: dict, *, url=None, allow_http=False, session_id=None, http_client=None):
        from .connection import connection_options
        options = connection_options(config, url=url, allow_http=allow_http)
        return cls(**options, session_id=session_id, http_client=http_client)

    @classmethod
    def from_env(cls):
        if os.getenv("AB_CONNECTION_CONFIG"):
            from .connection import read_connection
            if os.getenv("AB_RELAY") == "1":
                raise ValueError("Connection QR supports direct mode only")
            return cls.from_connection_config(read_connection(os.environ["AB_CONNECTION_CONFIG"]),
                       url=os.getenv("AB_URL"), allow_http=os.getenv("AB_ALLOW_HTTP") == "1",
                       session_id=os.getenv("AB_SESSION"))
        return cls(os.environ["AB_URL"], os.environ["AB_TOKEN"], os.environ["AB_DEVICE"],
                   relay=os.getenv("AB_RELAY") == "1", ca=os.getenv("AB_CA"),
                   allow_http=os.getenv("AB_ALLOW_HTTP") == "1", session_id=os.getenv("AB_SESSION"))

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_):
        await self.close()

    async def close(self):
        if self._owns_client:
            await self.http.aclose()

    async def request(self, operation: str, data: dict | None = None, *, timeout=15.0,
                      request_id: str | None = None, wait=True) -> dict:
        if not 0 < timeout <= 120:
            raise ValueError("timeout must be in (0, 120]")
        request_id = request_id or str(uuid.uuid4())
        body = dict(data or {})
        body.update(requestId=request_id, deviceId=self.device_id, sessionId=self.session_id,
                    deadline=int((time.time() + timeout) * 1000))
        path = (f"/devices/{self.device_id}" if self.relay else "") + "/api/v1/" + operation
        method = "GET" if operation in {"status", "capabilities", "apps", "foreground"} or operation.startswith("requests/") or (operation.startswith("jobs/") and not operation.endswith("/cancel")) else "POST"
        try:
            response = await self.http.request(method, self.url + path,
                headers={"Authorization": f"Bearer {self.token}"},
                **({"params": body} if method == "GET" else {"json": body}), timeout=timeout+2)
            response.raise_for_status()
            result = response.json()
        except (httpx.HTTPError, ValueError) as exc:
            raise BridgeException("UNKNOWN_RESULT", f"Request {request_id} sent once; query by requestId/jobId, do not automatically repeat a side effect ({type(exc).__name__})") from exc
        if (not operation.startswith("requests/") and result.get("requestId") != request_id) or result.get("deviceId") != self.device_id:
            raise BridgeException("PROTOCOL_ERROR", "Response identity mismatch", result)
        self.check(result)
        if wait and result.get("state") in ("queued", "running"):
            job_id = result["data"]["jobId"]
            until = time.monotonic() + timeout
            while time.monotonic() < until:
                await asyncio.sleep(0.15)
                info = await self.request(f"jobs/{job_id}", wait=False)
                job = info["data"]
                if job.get("result") is not None:
                    result = job["result"]
                    self.check(result)
                    return result
            raise BridgeException("TIMEOUT", f"Job {job_id} may still be executing; query or cancel, never replay", result)
        return result

    @staticmethod
    def check(result):
        if not result.get("success"):
            error = result.get("error") or {}
            raise BridgeException(error.get("code", "PROTOCOL_ERROR"), error.get("message", "Invalid result"), result)

    async def status(self):
        return (await self.request("status"))["data"]

    async def capabilities(self):
        return (await self.request("capabilities"))["data"]

    async def devices(self):
        if not self.relay:
            return [{"deviceId": self.device_id, "online": True, "status": await self.status()}]
        r = await self.http.get(self.url+"/devices", headers={"Authorization": f"Bearer {self.token}"})
        r.raise_for_status()
        return r.json()["devices"]

    async def screenshot(self, path: str | Path | None = None):
        image = (await self.request("screenshot", timeout=30))["data"]
        png = base64.b64decode(image.pop("imageBase64"), validate=True)
        if not png.startswith(b"\x89PNG\r\n\x1a\n"):
            raise BridgeException("PROTOCOL_ERROR", "Invalid PNG")
        self.last_image = image.copy()
        if path is not None:
            Path(path).write_bytes(png)
        return {**image, "png": png}

    def image_id(self, image_id: str | None):
        value = image_id or (self.last_image or {}).get("imageId")
        if value is None:
            raise ValueError("Take a screenshot first or pass image_id")
        return value

    async def tap(self, x: int, y: int, image_id: str | None = None):
        return await self.request("tap", dict(x=x,y=y,imageId=self.image_id(image_id)))

    async def long_press(self, x: int, y: int, image_id: str | None = None, duration_ms=700):
        return await self.request("long-press", dict(x=x,y=y,imageId=self.image_id(image_id),durationMs=duration_ms))

    async def swipe(self,x1:int,y1:int,x2:int,y2:int,image_id:str|None=None,duration_ms=300):
        return await self.request("swipe", dict(x1=x1,y1=y1,x2=x2,y2=y2,imageId=self.image_id(image_id),durationMs=duration_ms))

    async def text(self, text: str):
        return await self.request("text", {"text":text})

    async def key(self, key: str):
        return await self.request("key", {"key":key})

    async def apps(self):
        return (await self.request("apps"))["data"]

    async def launch(self, package_name: str):
        return await self.request("launch", {"packageName":package_name})

    async def foreground(self):
        return (await self.request("foreground"))["data"]

    async def ui_tree(self):
        return (await self.request("ui-tree", timeout=30))["data"]

    async def locate(self, *, resource_id=None, text=None, description=None):
        args = {k:v for k,v in dict(resourceId=resource_id,text=text,description=description).items() if v is not None}
        return (await self.request("locate", args, timeout=30))["data"]

    async def shell(self, argv: list[str], *, timeout=30, wait=True, output_limit=1048576):
        return await self.request("shell", {"argv":argv,"async":True,"outputLimit":output_limit}, timeout=timeout, wait=wait)

    async def query_request(self, request_id: str):
        return await self.request(f"requests/{request_id}", wait=False)

    async def job(self, job_id: str):
        return (await self.request(f"jobs/{job_id}", wait=False))["data"]

    async def cancel(self, job_id: str):
        return await self.request(f"jobs/{job_id}/cancel", wait=False)

    async def stop(self):
        return await self.request(f"sessions/{self.session_id}/stop", wait=False)
