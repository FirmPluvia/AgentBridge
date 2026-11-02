import httpx
from agentbridge.mcp_server import BearerGate

async def test_remote_mcp_requires_independent_token():
    seen=[]
    async def downstream(scope,receive,send):
        seen.append(True)
        from starlette.responses import JSONResponse
        await JSONResponse({'ok':True})(scope,receive,send)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=BearerGate(downstream,'m'*43)),base_url='https://mcp.example') as c:
        assert (await c.post('/mcp',json={})).status_code==401
        assert (await c.post('/mcp',json={},headers={'Authorization':'Bearer wrong'})).status_code==401
        assert seen==[]
        assert (await c.post('/mcp',json={},headers={'Authorization':'Bearer '+'m'*43})).status_code==200
        assert seen==[True]
