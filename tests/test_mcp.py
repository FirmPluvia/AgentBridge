import pytest
from mcp.types import ImageContent
from agentbridge import mcp_server

async def test_mcp_screenshot_image_content():
    class Device:
        async def screenshot(self):
            return {"png":b"\x89PNG\r\n\x1a\n", "imageId":"image", "width":1,"height":1}
    mcp_server._client=Device()
    try:
        content=await mcp_server.mcp.call_tool("screenshot",{})
        # FastMCP may also return structured content in a tuple.
        if isinstance(content,tuple):content=content[0]
        assert any(isinstance(c,ImageContent) and c.mimeType=="image/png" for c in content)
    finally:mcp_server._client=None
