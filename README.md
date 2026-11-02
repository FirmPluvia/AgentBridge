# AgentBridge 0.3.7

AgentBridge 是一套**直连式 Android 被控端**：在你自己的手机上装一个 App，
用 Root 或 Shizuku 执行截图、点击、滑动、按键、输入和可选的 shell，
控制端通过 Tailscale / 局域网里的 HTTPS API、Python SDK、CLI 或本地 stdio MCP
接入。不需要公网服务器，不需要端口映射，也不需要 ADB。

> **先说清楚边界**：拿到控制令牌的人就等于拿到了这台手机的完全控制权。
> 令牌、导出的证书（`bridge.pem`）只走可信渠道传给可信的控制端，
> 绝不要贴到公开聊天、Issue 或提交进 Git 仓库。
> 本项目未经独立安全审计，不要把它当作高安全生产控制平台。

## 能做什么

| 能力 | 说明 |
| --- | --- |
| 截图 | `screencap` 原始 PNG，返回给控制端；默认不落在手机上 |
| 点击 / 长按 / 滑动 / 按键 | 按最近一张截图的真实像素坐标校验，含返回、主页、最近任务、回车、音量 |
| 输入 | 需用户在手机上主动启用 AgentBridge 辅助输入法；支持中文、多行与特殊字符 |
| UI 定位 | `ui-tree` / `locate`，按 resourceId / 文本 / 描述精确匹配 |
| 应用与前台 | 列出应用、启动应用、查询前台包名（ROM 可能不支持） |
| shell（可选） | 默认关闭，需在手机本地明确开启；跟随当前执行后端，不另选后端 |

执行后端只有两种：**Root**（KSU / ReKSU，libsu RootService，UID 0）
或 **Shizuku**（官方 UserService，通常 UID 2000）。
**ADB 模式已在 0.2.0 移除**，升级后旧的 ADB 配置会变成“未选择”，
必须明确选 Root 或 Shizuku，不会静默切换到更高权限。

## 不能做什么

- 不绕过锁屏、FLAG_SECURE 保护页、DRM、安全键盘；这类页面可能只截到黑图，
  黑图本身不能用来断定就是受保护页面。
- 不关闭 SELinux、不改系统分区、不自动申请 root：远端请求不会在手机上
  弹出 root 授权，必须先在手机本地授权并连接。
- 当前 APK **没有公网中转入口**，也不会主动连到任何中转服务器。
  `relay/` 里的旧中转服务代码只是独立归档，当前 APK 不用它；
  云端 Agent 如果没有加入你的 tailnet，贴一个 100.x 地址也连不上。
- 同一时间只有一个 UI 控制会话；已发生的点击、输入无法撤销；
  截图超过 30 秒、旋转或尺寸变化后，旧的 `imageId` 会被拒绝。

## 快速开始：Tailscale 直连

完整分步说明见 [docs/TAILSCALE.md](docs/TAILSCALE.md)。基本流程：

### 1. 手机端准备

1. 被控手机和控制端都装官方 Tailscale，登录**同一 tailnet**，
   确认 ACL 允许控制端访问手机的 TCP 8443。
2. AgentBridge → 权限：选 Root 或 Shizuku，点「申请/重试连接」，
   等到显示 **已就绪 · 执行自检通过**（Root 应为 UID 0）。
   只显示“授权 UID”但服务未就绪，不等于能执行。
3. AgentBridge → 连接：监听 IP 填手机的 Tailscale IPv4
   （例如 `100.88.12.34`，也可点「自动选择候选 IP」），端口 `8443`，
   保留 HTTPS，点「保存连接设置」。
   如果提示该地址无法绑定：先停服务，明确选「监听所有接口」再保存重启，
   控制端仍访问手机的 100.x 地址，不要把 `0.0.0.0` 当远程目标。
4. 连接页复制 PEM 证书，安全页批准并复制控制令牌，首页复制设备 ID，
   首页启动服务。

### 2. 控制端验证

```bash
python -m venv .venv
source .venv/bin/activate
pip install -c requirements.lock .

export AB_URL='https://100.88.12.34:8443'
export AB_DEVICE='首页的设备ID'
export AB_CA="$PWD/bridge.pem"
read -rs AB_TOKEN; export AB_TOKEN
unset AB_RELAY AB_ALLOW_HTTP

agentbridge status
agentbridge screenshot --output screen.png
```

同一会话里先看截图、再点目标位置（坐标必须是这张图的实际像素）：

```bash
export AB_SESSION='control-20261101-1'
agentbridge screenshot --output screen.png
# 查看 screen.png，把 300、500 换成目标坐标，image 换成刚返回的 imageId
agentbridge tap 300 500 --image '刚才的imageId'
agentbridge stop
```

Python SDK 同理，SDK 会自动携带同一客户端最近截图的 `imageId`：

```python
import asyncio
from agentbridge import AgentBridge

async def main():
    async with AgentBridge.from_env() as phone:
        shot = await phone.screenshot('screen.png')
        print(shot['width'], shot['height'], shot['imageId'])
        await phone.tap(300, 500)  # 先看图，再把坐标换成实际目标
        await phone.stop()

asyncio.run(main())
```

MCP：在已接入该 tailnet 的电脑上跑 `agentbridge-mcp`（stdio），
传入同样的 `AB_URL` / `AB_DEVICE` / `AB_TOKEN` / `AB_CA`。
不要用 `--allow-http` 绕过证书校验；可信网络 HTTP 是显式高级选项，
且证书、令牌仍是必需的。

## 0.3.7 改了什么

- 补齐 miuix 弹窗显示层：根层显式挂载 `MiuixPopupHost`，
  修复保存结果、错误、关于及监听确认等弹窗在 Material Scaffold 下不显示的问题；
  弹窗文字跟随当前深浅色主题，操作结果不自动消失。
- 权限按钮上方按当前选择显示真实状态：连接和 UID 有效时显示
  「当前执行后端：Root / Shizuku」，否则显示对应的「未授权」；
  执行 UID、进度和失败原因保留在详细状态里。
- 当前后端的连接快照单独采集，不读另一后端的缓存；连接失效后
  不再继续展示旧的「已就绪」详情。

更早的版本记录见 [docs/RELEASE.md](docs/RELEASE.md)，
0.3.0–0.3.6 的界面、主题（miuix 0.5.2 / Material 3 双主题）、
连接页重排、shell 跟随执行后端等改动都在源码与验证记录里有据可查，
这里不再逐条复述。

包名与安装注意：本仓库源码的包名已改为 `starx.dev.agentbridge`
（`android/app/build.gradle.kts` 里的 namespace 与 applicationId，
versionCode 10、versionName 0.3.7，minSdk 26 / Android 8.0；
Java / Kotlin / AIDL 源码的包声明也已全部改为新包名，签名配置未动）。
因此它**不是**旧包名 `dev.agentbridge` 的覆盖升级，
会作为一个新应用安装：需要重新授权 Root / Shizuku、重新保存连接设置、
重新导出证书与令牌；旧应用确认迁移完成后再卸载，
卸载会丢失旧应用的设备身份及授权。

## 构建与测试

固定工具链：JDK 17、Gradle 8.13、AGP 8.13.2、Kotlin 2.2.21、
SDK 36 / Build Tools 36.0.0。依赖全部固定版本，无私有路径依赖。

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
sdkmanager 'platforms;android-36' 'build-tools;36.0.0' 'platform-tools'

chmod +x android/gradlew scripts/build.sh  # 从 GitHub 克隆后若执行权限丢失，先补这一句
./scripts/build.sh        # 生成调试签名 APK 到 artifacts/
pip install -c requirements.lock '.[test]'
pytest -q
```

也可以用 Android Studio 打开 `android/` 目录构建（需要 JDK 17，
只有 JRE、没有 javac 的安装不行）。

不要提交 `local.properties`、`.env`、构建产物和签名文件。
push / PR / 手动运行会触发 `.github/workflows/build.yml`：
跑 Python 测试、Android 单元测试、lint 并构建 debug APK，
产物与 SHA256SUMS 在 Actions 的 Artifacts 里下载。

## 发布：正式签名

推送 `v*` tag 会触发 `.github/workflows/release.yml`。
正式签名材料先在可信本地生成并妥善保管，不要进仓库，
再到仓库 Settings → Secrets and variables → Actions 设置：

| Secret | 值 |
| --- | --- |
| `AB_KEYSTORE_BASE64` | keystore 文件完整 Base64（`base64 -w0 release.jks`，不要提交输出） |
| `AB_STORE_PASSWORD` | keystore 密码 |
| `AB_KEY_ALIAS` | 签名 alias |
| `AB_KEY_PASSWORD` | 私钥密码 |

四项齐全才产出签名 APK；缺任何一项，产物会明确命名为
`AgentBridge-release-UNSIGNED.apk`，**未签名产物不能直接安装**，
不要用调试签名冒充正式发布。

## 仓库结构

```
android/                  Android 被控端（Kotlin + Compose，miuix / Material 3）
python/agentbridge/       Python SDK、CLI（agentbridge）、MCP（agentbridge-mcp）
relay/                    旧公网中转服务归档，当前 APK 不启用、不连接
docs/                     API、能力、安全、构建、发布、验证等文档
tests/                    Python 测试（含旧 relay 协议测试，仅作归档）
.github/workflows/        build.yml（push/PR 构建 debug APK）、release.yml（tag 发布）
scripts/                  build.sh、package_source.py、package_delivery.py
```

## 文档索引

- [docs/TAILSCALE.md](docs/TAILSCALE.md) — 从安装到第一次点击的完整直连流程与故障排查
- [docs/API.md](docs/API.md) / [docs/openapi.json](docs/openapi.json) — `/api/v1` 接口、错误码与会话规则
- [docs/CAPABILITIES.md](docs/CAPABILITIES.md) — Root / Shizuku 能力对照表
- [docs/SECURITY.md](docs/SECURITY.md) — 安全模型、限流与已知未做项
- [docs/BUILD.md](docs/BUILD.md) — 构建、CI 与正式签名细节
- [docs/RELEASE.md](docs/RELEASE.md) — 本版发布说明
- [docs/VALIDATION.md](docs/VALIDATION.md) — 已验证项与仍需真机验收项
- [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) — 旧公网中转部署资料（0.1.x，当前停用）

## 安全与验证：如实说明

- 直连默认 HTTPS，导出自签名证书后控制端以 `--ca` 信任并校验 SAN 与主机名；
  本项目没有 TrustAll、没有关闭主机名校验。
- 控制请求限流：每控制端每分钟 120 请求；HTTP body 最大 64 KiB；
  shell 最长 30 秒、输出最大 1 MiB；截图原始数据最大 16 MiB。
- 审计日志只记时间、控制端 ID、工具、后端、耗时与结果（内存最多 300 条），
  没有远程遥测；截图不默认落盘。
- 0.3.7 已通过：Android debug / unsigned release 构建、lint、
  46 项 JVM 单元测试、11 项 Python 测试（见 [docs/VALIDATION.md](docs/VALIDATION.md)）。
  但**没有实际 Compose 弹窗点击测试或真机 Binder 运行验收**，
  miuix 弹窗、Root / Shizuku 授权与退出、Tailscale 跨网络、
  中文输入与旋转等仍需在真机上按 VALIDATION.md 的清单验收，
  编译与单元测试通过不等于实机通过。

## 许可

源码按 Apache-2.0 分发，见 [LICENSE](LICENSE)。
第三方组件与许可见 [NOTICE](NOTICE)（libsu、Shizuku、miuix、
Bouncy Castle、NanoHTTPD、OkHttp、AndroidX / Compose、Kotlin 等）。
