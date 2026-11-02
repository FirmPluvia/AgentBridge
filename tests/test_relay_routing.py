from concurrent.futures import ThreadPoolExecutor
import time
from fastapi.testclient import TestClient
from relay.app import create_app
from test_relay import D,T,headers,register,pair

def test_routing_result_and_permission_is_not_faked_by_relay():
    with TestClient(create_app(':memory:'),base_url='https://testserver') as c:
        register(c)
        with c.websocket_connect(f'wss://testserver/device/ws?deviceId={D}',headers=headers(T)) as ws:
            token=pair(c,ws)
            request={'requestId':'test-request','deviceId':D,'deadline':int(time.time()*1000)+2000,'sessionId':'s'}
            with ThreadPoolExecutor(1) as pool:
                future=pool.submit(lambda:c.post(f'/devices/{D}/api/v1/screenshot',json=request,headers=headers(token)))
                p=ws.receive_json()
                assert p['type']=='request' and p['path']=='/api/v1/screenshot'
                assert p['controllerId']
                result=dict(requestId='test-request',deviceId=D,backend='shizuku',success=False,durationMs=1,state='failed',data=None,error={'code':'PERMISSION_DENIED','message':'UserService not authorized','retryable':False})
                ws.send_json({'type':'result','routeId':p['routeId'],'result':result})
                assert future.result(4).json()==result
