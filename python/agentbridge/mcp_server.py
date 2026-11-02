"""Local stdio or bearer-authenticated remote Streamable HTTP MCP."""
import argparse
import os
import secrets
import uvicorn
from mcp.server.fastmcp import FastMCP, Image
from starlette.responses import JSONResponse
from mcp.server.transport_security import TransportSecuritySettings
from .client import AgentBridge

mcp=FastMCP("AgentBridge", host="127.0.0.1", port=8765, stateless_http=True, transport_security=TransportSecuritySettings(allowed_hosts=["127.0.0.1:*","localhost:*"]+list(filter(None,os.getenv("AB_MCP_HOSTS","").split(","))), allowed_origins=list(filter(None,os.getenv("AB_MCP_ORIGINS","").split(",")))))
_client=None

def client():
    global _client
    if _client is None:
        _client=AgentBridge.from_env()
    return _client

@mcp.tool()
async def status() -> dict:
    """Read actual Android device/backend UID and current session. Requires view scope."""
    return await client().status()

@mcp.tool()
async def capabilities() -> dict:
    """Inspect real backend availability, conditional features and shell permission."""
    return await client().capabilities()

@mcp.tool()
async def screenshot() -> list:
    """Capture actual PNG and metadata. Coordinates are image pixels; imageId expires after 30s or rotation. Protected content cannot be bypassed. Requires view scope; timeout 30s."""
    shot=await client().screenshot()
    png=shot.pop("png")
    return [shot, Image(data=png,format="png")]

@mcp.tool()
async def tap(x:int,y:int,image_id:str) -> dict:
    """Click screen using actual pixels of recent screenshot, with its imageId. UI scope. Side effect; timeout does not mean unexecuted. Never automatically retry."""
    return await client().tap(x,y,image_id)

@mcp.tool()
async def long_press(x:int,y:int,image_id:str,duration_ms:int=700) -> dict:
    """Long press recent screenshot pixels; UI permission, side effect, no retry on unknown result."""
    return await client().long_press(x,y,image_id,duration_ms)

@mcp.tool()
async def swipe(x1:int,y1:int,x2:int,y2:int,image_id:str,duration_ms:int=300) -> dict:
    """Swipe actual screenshot pixels. imageId <=30s and same rotation; UI scope, side effect."""
    return await client().swipe(x1,y1,x2,y2,image_id,duration_ms)

@mcp.tool()
async def text(value:str) -> dict:
    """Input Unicode/multiline text into focused editor. Requires locally enabled AgentBridge IME and UI scope. Temporarily switches/restores IME; secure keyboards may reject. Side effect, never replay after timeout."""
    return await client().text(value)

@mcp.tool()
async def key(name:str) -> dict:
    """System key: back/home/recents/enter/volume-up/volume-down. UI permission and side effect."""
    return await client().key(name)

@mcp.tool()
async def apps() -> list:
    """List launcher-visible installed apps; view scope."""
    return await client().apps()

@mcp.tool()
async def launch(package_name:str) -> dict:
    """Launch package main activity; UI scope and side effect, never retry an unknown outcome."""
    return await client().launch(package_name)

@mcp.tool()
async def foreground() -> dict:
    """Read foreground application using selected privileged backend. Permission/ROM errors are explicit."""
    return await client().foreground()

@mcp.tool()
async def ui_tree() -> dict:
    """Get available UI hierarchy (30s timeout); permission/ROM restrictions may make unavailable. Fall back to screenshot coordinates."""
    return await client().ui_tree()

@mcp.tool()
async def locate(resource_id:str|None=None,text:str|None=None,description:str|None=None) -> dict:
    """Locate exact matching visible UI nodes and bounds; screenshot needed before clicking their pixel centers."""
    return await client().locate(resource_id=resource_id,text=text,description=description)

@mcp.tool()
async def shell(argv:list[str],timeout_seconds:float=30) -> dict:
    """Advanced shell, DEFAULT DISABLED. Requires explicit local approval, the currently selected execution backend and controller shell scope. argv is an argument array, no interpolation. Max 30s command and 1MiB output. Returns async jobId; execution may have side effects, never retry."""
    return await client().shell(argv,timeout=min(timeout_seconds,30),wait=False)

@mcp.tool()
async def job(job_id:str) -> dict:
    """Read async job state/result. A timeout/unknown outcome is not proof of nonexecution."""
    return await client().job(job_id)

@mcp.tool()
async def query_request(request_id:str) -> dict:
    """Query an accepted requestId after transport failure; stored unknown is not proof of nonexecution. Do not reissue side effects."""
    return await client().query_request(request_id)

@mcp.tool()
async def cancel(job_id:str) -> dict:
    """Cancel queued job or request termination. Cannot undo clicks/text already performed."""
    return await client().cancel(job_id)

@mcp.tool()
async def stop_session() -> dict:
    """Stop this control session, cancel queue and terminable tasks; already occurred actions cannot be undone."""
    return await client().stop()

class BearerGate:
    def __init__(self,app,token): self.app,self.token=app,token
    async def __call__(self,scope,receive,send):
        if scope["type"]=="http":
            auth=dict(scope["headers"]).get(b"authorization",b"")
            if not secrets.compare_digest(auth,b"Bearer "+self.token.encode()):
                await JSONResponse({"error":"UNAUTHORIZED"},401)(scope,receive,send);return
        await self.app(scope,receive,send)

def main():
    p=argparse.ArgumentParser();p.add_argument("--http",action="store_true");p.add_argument("--host",default="127.0.0.1");p.add_argument("--port",type=int,default=8765);args=p.parse_args()
    if args.http:
        token=os.environ.get("AB_MCP_TOKEN","")
        if len(token)<40:raise SystemExit("Remote HTTP MCP requires independent high-entropy AB_MCP_TOKEN (>=40 chars); terminate TLS at trusted reverse proxy")
        app=BearerGate(mcp.streamable_http_app(),token)
        uvicorn.run(app,host=args.host,port=args.port,access_log=False)
    else:mcp.run(transport="stdio")
