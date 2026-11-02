import argparse
import asyncio
import json
import os
import time
import httpx
from .client import AgentBridge

async def run(args):
    if args.command == "pair":
        if not args.url.startswith("https://"):
            raise ValueError("Pairing requires HTTPS")
        async with httpx.AsyncClient() as http:
            r = await http.post(args.url.rstrip("/")+"/pairings",json={"deviceId":args.device,"name":args.name});r.raise_for_status();p=r.json()
            print("请在被控手机本地核对并确认校验码：", p["code"], flush=True)
            until=time.monotonic()+p["expiresIn"]
            while time.monotonic()<until:
                await asyncio.sleep(1)
                r=await http.get(args.url.rstrip("/")+"/pairings/"+p["pairingId"],headers={"Authorization":"Bearer "+p["pollSecret"]});r.raise_for_status();value=r.json()
                if value["approved"] is not None:
                    print(json.dumps(value,ensure_ascii=False));return
            raise TimeoutError("Pairing expired")
    async with AgentBridge(args.url,args.token,args.device,relay=args.relay,ca=args.ca,allow_http=args.allow_http,session_id=args.session) as bridge:
        cmd=args.command
        if cmd=="status": value=await bridge.status()
        elif cmd=="devices": value=await bridge.devices()
        elif cmd=="capabilities":value=await bridge.capabilities()
        elif cmd=="screenshot":value=await bridge.screenshot(args.output);value.pop("png")
        elif cmd=="tap":value=await bridge.tap(args.x,args.y,args.image)
        elif cmd=="text":value=await bridge.text(args.value)
        elif cmd=="shell":value=await bridge.shell(args.argv[1:] if args.argv[:1]==["--"] else args.argv)
        elif cmd=="stop":value=await bridge.stop()
        print(json.dumps(value,ensure_ascii=False,indent=2))

def main():
    p=argparse.ArgumentParser(description="AgentBridge：坐标为最近截图实际像素；副作用不会自动重试")
    p.add_argument("--url",default=os.getenv("AB_URL"));p.add_argument("--token",default=os.getenv("AB_TOKEN"));p.add_argument("--device",default=os.getenv("AB_DEVICE"));p.add_argument("--relay",action="store_true",default=os.getenv("AB_RELAY")=="1");p.add_argument("--ca",default=os.getenv("AB_CA"));p.add_argument("--allow-http",action="store_true",default=os.getenv("AB_ALLOW_HTTP")=="1");p.add_argument("--session",default=os.getenv("AB_SESSION"))
    subs=p.add_subparsers(dest="command",required=True)
    for c in ("status","devices","capabilities","stop"):subs.add_parser(c)
    s=subs.add_parser("screenshot");s.add_argument("--output",default="screen.png")
    s=subs.add_parser("tap");s.add_argument("x",type=int);s.add_argument("y",type=int);s.add_argument("--image",required=True)
    s=subs.add_parser("text");s.add_argument("value")
    s=subs.add_parser("shell");s.add_argument("argv",nargs=argparse.REMAINDER)
    s=subs.add_parser("pair");s.add_argument("--name",default="Agent controller")
    args=p.parse_args()
    if not args.url or not args.device or (args.command!="pair" and not args.token):p.error("需要 --url、--device 和 --token（配对不需要 token）")
    asyncio.run(run(args))
