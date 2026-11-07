# Agent初始化与精简连接提示词（0.5）

## 最少准备

1. 被控手机已在AgentBridge授权Root或Shizuku，保存监听设置并启动服务。
2. agent的工具执行环境能访问手机：处于同一Wi-Fi/热点，或已加入同一Tailscale网络。聊天界面所在的手机联网，不会自动让云端工具环境获得私网访问能力。
3. agent具备Python 3.11+和终端，或能使用已部署的MCP服务；提供源码包中的SDK及扫码JSON。
4. 新二维码包含version、deviceId、urls、token、certificatePem五项。扫码JSON保存为受保护的connection.json；不要把令牌写入公开聊天、日志或Git。

## 初始化

在解压后的AgentBridge源码目录执行，不是从未知同名PyPI项目安装：

```bash
pip install -c requirements.lock .
agentbridge --connection-config connection.json status
agentbridge --connection-config connection.json capabilities
agentbridge --connection-config connection.json screenshot --output screen.png
```

status确认设备和后端，capabilities确认实际能力，截图后才按真实像素操作。网络错误或超时不能视为确定未执行，不自动重放点击、输入或shell。

如果有多个urls，控制端显式选择可达的地址，不依次重放副作用请求。HTTPS始终验证证书和主机名；HTTP必须显式使用--allow-http。手机不需要保存模型API Key。

## 0.5精简提示词

二维码页展示和复制的内容为：

```text
读取二维码/JSON为connection.json，勿公开令牌。工具端先接入同一局域网/热点或Tailscale，安装源码SDK后执行：
agentbridge --connection-config connection.json status
再查capabilities、截图后按实际像素操作；保留TLS校验，超时不重放点击。
```

提示词只说明步骤，不含真实令牌、证书或连接地址；须与二维码或扫码JSON一起提供。agent无法解码二维码时，可改用可信扫码工具导出的JSON。

## MCP

安装同一源码包后，设置以下环境变量再启动本地stdio MCP：

```bash
export AB_CONNECTION_CONFIG="$PWD/connection.json"
unset AB_URL AB_TOKEN AB_DEVICE AB_RELAY AB_CA
agentbridge-mcp
```

远程HTTP MCP的HTTPS、访问鉴权和允许主机仍按既有部署说明配置。不要把被控手机的令牌误当作MCP服务自身的访问令牌。

更多网络配置见HELP.md，二维码格式和旧格式兼容见CONNECTION_QR.md。
