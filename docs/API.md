# 0.2.0 使用说明

当前APK仅支持直连 `/api/v1`，后端为Root/Shizuku；下文中转前缀为旧独立服务协议存档，不是当前APK的连接能力。

# 执行协议 v1

直连前缀 `/api/v1`；中转前缀 `/devices/{deviceId}/api/v1`。所有请求 `Authorization: Bearer <controllerToken>`，TLS 默认强制。传输不改变 body 和结果结构。WebSocket 直连与中转控制端均发送 `{"path":"/api/v1/tap","body":{...}}`；中转控制端入口 `/controllers/ws`，手机专用 `/device/ws?deviceId=...` 使用独立设备凭证，不能代替控制端凭证。

POST body / GET query 公共字段：`requestId`（UUID，最长128）、`deviceId`、`sessionId`、`deadline`（Unix 毫秒，最长未来120秒）。直连兼容只读请求自动补 requestId/deadline；SDK 始终提供。界面操作必须有 sessionId。

结果：

```json
{"requestId":"uuid","deviceId":"uuid","backend":"root","success":true,"durationMs":51,"state":"completed","data":{"executed":true},"error":null}
```

错误统一 `error: {code,message,retryable}`。可能 code：UNAUTHORIZED、PERMISSION_DENIED、BACKEND_UNAVAILABLE、DEVICE_OFFLINE、TIMEOUT、CANCELLED、UNSUPPORTED、STALE_SCREEN、OUTPUT_LIMIT_EXCEEDED；额外包含 INVALID_ARGUMENT、RATE_LIMITED、SESSION_BUSY、UNKNOWN_RESULT。失败结果与 HTTP 状态共同处理：网络鉴权失败为401，结构化执行错误通常为200且 success=false；中转 HTTP 层 FastAPI 校验错误遵循其 HTTP 状态码。

| 方法 | 路径 | 额外字段 / data | 权限 |
|---|---|---|---|
| GET | /status | 型号、Android、后端实际检测状态、会话、监听、最近屏幕尺寸 | view |
| GET | /capabilities | available、实现的操作、条件限制、shell 开关 | view |
| POST | /screenshot | data 含 imageId,width,height,rotation,timestamp,mimeType,imageBase64 | view |
| POST | /ui-tree | data.xml | view |
| POST | /locate | resourceId/text/description，精确 AND 匹配；返回 matches + bounds | view |
| POST | /tap | x,y,imageId | ui |
| POST | /long-press | x,y,imageId,durationMs=700 | ui |
| POST | /swipe | x1,y1,x2,y2,imageId,durationMs=300 | ui |
| POST | /text | text，最多16000字符，依赖用户启用辅助 IME | ui |
| POST | /key | key: back/home/recents/enter/volume-up/volume-down | ui |
| GET | /apps | 包名及 launcher label | view |
| POST | /launch | packageName | ui |
| GET | /foreground | data.packageName，ROM 可能不支持 | view |
| POST | /shell | argv 数组；outputLimit 1KiB–1MiB；async 默认 true | shell |
| GET | /jobs/{id} | jobId,state,result；仅任务所属控制端 | view |
| POST | /jobs/{id}/cancel | 取消排队/请求终止；不能撤销操作 | ui |
| GET | /requests/{requestId} | 查询已有请求结果或 unknown；禁止重新执行 | view |
| POST | /sessions/{id}/stop | 取消该会话任务并释放占用；仅原控制端 | ui |

截图 PNG 作为原始 ByteArray 获取，Base64 仅作 JSON 的无损二进制封装；从不把 PNG 当 UTF-8 字符串解码。SDK 返回 `png: bytes`，MCP 返回图像。默认不落盘；UI hierarchy 因 uiautomator 限制使用独立随机临时 XML，finally 删除，不承诺设备突然断电后能清理临时 XML。

坐标为最新截图真实像素。边界必须满足 `0 <= x < width`、`0 <= y < height`，imageId 相同、30秒内、方向及逻辑尺寸不变；映射到 `wm size` 的当前输入逻辑尺寸。仅支持默认显示屏；旋转、ROM 显示报告异常时返回 STALE_SCREEN / UNSUPPORTED。安全屏幕可能返回黑图；通用方式无法可靠区分正常黑屏和 FLAG_SECURE，禁止宣称黑图为可控制的受保护内容。

## 任务及会话

同一手机的 UI / shell 副作用串行，读取使用最多3线程。首个副作用请求占用 `(controllerId,sessionId)`，其他会话收到 SESSION_BUSY；占用持续到 stop 或本地停止。SDK / CLI 跨进程操作需设置相同 `AB_SESSION`，否则上一会话需先停止。

shell 长操作返回 `state=queued,data.jobId`。查询 job 最终得到 stdout、stderr、exitCode、executionState。命令最长30秒，普通操作默认15秒（截图/UI树30秒）；输出有上限。执行超时/断线的副作用结果为 unknown，不能理解成未执行。SDK 不重试网络失败；仅可重复读任务状态。

requestId 在设备持久 SQLite 中先登记后执行；同一控制端重复 ID 返回缓存结果，重启或缓存淘汰后返回 UNKNOWN_RESULT，绝不自动重做。登记后崩溃、执行前崩溃也保守返回 unknown。不能保证所有命令都 exactly once，但保证已登记请求不会自动二次执行。中转无离线操作队列，断线不重放。有限内存保留一部分完成任务，去重墓碑不随任务淘汰而删除。

本地停止先取消队列，再调用后端终止；Root 和 Shizuku 通过本地特权服务请求终止受控进程。目标命令自行脱离/守护化的进程可能存活，结果如实标记 unknown；没有“撤销已发生点击”的承诺。

## 可复制 HTTP 示例

```bash
# AB_URL / AB_DEVICE / AB_TOKEN / AB_CA 已按 README 配置
RID=$(python -c 'import uuid;print(uuid.uuid4())')
DEADLINE=$(python -c 'import time;print(int(time.time()*1000)+15000)')
curl --cacert "$AB_CA" --get "$AB_URL/api/v1/status" \
  -H "Authorization: Bearer $AB_TOKEN" \
  --data-urlencode "requestId=$RID" --data-urlencode "deviceId=$AB_DEVICE" \
  --data-urlencode "deadline=$DEADLINE"
# 更推荐 SDK，它会生成 requestId/deadline 并在点击时携带 imageId。
```

中转 URL 把 `/api/v1` 替换为 `/devices/$AB_DEVICE/api/v1`，可信公网证书无需 `--cacert`。不要把请求头、Tailscale IP 或100.x网段作为身份凭证。

连接二维码是本地用户确认后的配置导出，不增加远程读取令牌的HTTP端点。SDK导入格式与说明见[CONNECTION_QR.md](CONNECTION_QR.md)。
