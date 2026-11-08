# AgentBridge 0.5

- **真机操控**：Root / Shizuku 执行真实截图、点击、滑动、按键、中文输入、UI hierarchy 查询；可选 shell（默认关闭）。
- **直连，不走公网**：Tailscale / 局域网 HTTPS 直连，无公网中转；HTTPS 默认开启，证书须与访问 IP / DNS 匹配。
- **三种接入**：HTTPS API、Python SDK / CLI、本地 stdio MCP；连接二维码扫码导入配置。
- **一键自检**：逐项检查配置、证书、后端 UID、截图、输入与鉴权，只报告结果。
- **双主题**：Miuix / Material 3，深浅色跟随系统。
- **两个包**：debug（`dev.agentbridge`，调试签名）与 release（`starx.dev.agentbridge`，starx 密钥签名），独立安装。release 不再核验证书指纹；缺少签名密钥时改用调试密钥签名，产物标明 debug。

详见 [README](../README.md) 与 [签名约定](SIGNING.md)。
