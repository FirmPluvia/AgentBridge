import base64
import httpx
import pytest
from agentbridge import AgentBridge, BridgeException

@pytest.mark.parametrize("relay",[False,True])
async def test_protocol_and_binary(relay):
    seen=[]
    png=b"\x89PNG\r\n\x1a\n\x00\xff\x80"
    def handler(req):
        import json
        p=json.loads(req.content) if req.method=="POST" else dict(req.url.params)
        seen.append((req.url.path,p))
        assert req.headers["authorization"]=="Bearer secret"
        assert p["sessionId"]=="session" and p["deviceId"]=="device"
        data={"imageId":"image","width":100,"height":200,"rotation":0,"timestamp":1,"imageBase64":base64.b64encode(png).decode()} if req.url.path.endswith("screenshot") else {"executed":True}
        return httpx.Response(200,json=dict(requestId=p["requestId"],deviceId="device",success=True,data=data,state="completed",error=None))
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
        b=AgentBridge("https://example.com","secret","device",relay=relay,session_id="session",http_client=http)
        assert (await b.screenshot())["png"]==png
        await b.tap(5,8)
    assert seen[-1][1]["imageId"]=="image"
    assert seen[-1][0]==("/devices/device" if relay else "")+"/api/v1/tap"

async def test_failure_does_not_retry():
    count=0
    def handler(req):
        nonlocal count
        count+=1
        raise httpx.ReadTimeout("disconnected")
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
        b=AgentBridge("https://example.com","secret","device",http_client=http)
        with pytest.raises(BridgeException,match="UNKNOWN_RESULT"):
            await b.tap(1,2,"image")
    assert count==1

def test_http_opt_in_and_no_plain_relay():
    with pytest.raises(ValueError):AgentBridge("http://example.com","token","device")
    with pytest.raises(ValueError):AgentBridge("http://example.com","token","device",relay=True,allow_http=True)

async def test_identity_validation():
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda r:httpx.Response(200,json={"requestId":"other","deviceId":"device","success":True}))) as http:
        b=AgentBridge("https://example.com","secret","device",http_client=http)
        with pytest.raises(BridgeException,match="PROTOCOL_ERROR"):await b.status()
