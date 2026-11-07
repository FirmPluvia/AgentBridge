# AgentBridge 0.5 · Tailscale 直连版

Android 被控端通过 **Root 或 Shizuku** 执行真实截图、屏幕点击、滑动、按键、中文输入、UI hierarchy 和可选 shell。Agent 通过 Tailscale / 局域网中的 HTTPS API、Python SDK、CLI 或本地 stdio MCP 接入。

**当前版本已移除 ADB 模式及相关原生库。APK 当前只做直连，没有公网中转入口或主动 WSS 中转连接。** `relay/` 与 SDK 的旧中转协议代码暂作独立归档，当前 APK 不与其连接，无需部署服务器。

## 安装与升级

下载页： https://agentbridge-download-20261101-b7d2.actual-2-trendy.chatgpt.site （使用拥有该站点的 ChatGPT 账户登录）。选择0.5 APK压缩包，解压安装。minSdk26 / Android8.0，debug版本已签名；版本号14，可覆盖同一调试签名的0.1.0，通常无需卸载。

原来选择 ADB 的配置升级后变为“未选择”，需要明确选择 Root 或 Shizuku，不会静默切换到更高权限。原来保存的公网连接模式会切换到直连。

## 0.5 更新

- 设置按钮改为“连接帮助”；首页连接类型改为“局域网·Tailscale”。版本名保持0.5，版本码14。
- debug保持dev.agentbridge及默认调试签名；release改为starx.dev.agentbridge，独立安装，严格核验starx证书。辅助输入法组件名兼容两种包名。

- Miuix首页及各页统一采用自身的标题层级、分组卡片、连续圆角和原生控件；移除旧Material首页混用。
- Material 3使用原生字体角色、表面色阶、胶囊按钮、勾选分段按钮及导航指示器，统一各页面的间距和对齐。
- 精简二维码页Agent提示词，保留工具端联网、SDK、状态/能力/截图、TLS及超时不重放要求。
- 源码包新增[双主题UI说明](docs/UI_DESIGN_0.5.md)及[Agent初始化说明](docs/AGENT_START.md)，便于理解设计与首次接入。

版本码14，可覆盖原调试签名安装；二维码格式仍为2，旧格式1导入兼容性保留。

## 0.4 更新

- 二维码升级为格式2，只保留协议版本、设备ID、连接地址、令牌、完整证书五项；SDK/CLI/MCP继续兼容旧格式1，TLS校验保持开启，HTTP仍须显式允许。
- 二维码页提供可复制的agent连接提示词，不重复嵌入实际令牌；允许截屏，保留生成前风险确认与后台隐藏。
- 设置页新增面向新手的连接帮助：同一Wi-Fi、被控手机开热点、控制端开热点、Tailscale及常见问题。
- 主题显示名统一为Miuix，保留旧主题偏好；App版本0.4，版本码12，可覆盖升级。

使用步骤见[连接帮助](docs/HELP.md)及[精简二维码](docs/CONNECTION_QR.md)。

## 0.3.8 更新

- 首页新增一键自检，逐项检查配置/证书、后端实际UID、真实截图、输入命令、辅助IME及本机API/鉴权；可取消，不点击、不输入、不自动申请授权或启动监听。
- 连接页新增完整配置二维码，默认隐藏，风险确认后才生成。包含真实地址、设备ID、当前令牌、完整TLS公钥证书与指纹、权限和后端元数据，不含私钥。
- 二维码离页/后台/配置变更/凭证撤销后隐藏；不写文件或日志，请求系统保护显示窗口。容量不足时不删减内容。
- Python SDK、CLI及MCP支持扫码JSON配置导入，仍校验证书和主机名。自检区分通过、注意、失败、未检查，未发送实际点击不会冒称触控实测通过。
- 首页连接提示补充扫码与局域网/热点方式。使用步骤见[自检与二维码](docs/CONNECTION_QR.md)。

## 0.3.7 更新

- 补齐miuix弹窗显示层：当前应用使用Material Scaffold，需在根层显式挂载miuix MiuixPopupHost，修复保存结果、错误、关于及监听确认等弹窗未显示的问题。
- 弹窗显示层与主题共享正确的前景色，支持深浅色；没有给操作结果添加自动消失计时。
- 权限按钮上方按当前选择显示状态：连接和UID有效时显示“当前执行后端：Root/Shizuku”，否则显示对应“未授权”；执行UID、进度和失败原因保留在详细状态中。
- 当前后端的连接快照单独采集，不读取另一后端的缓存状态；连接已失效时不会继续展示旧“已就绪”详情。

## 0.3.6 更新

- 删除权限页的恢复配置提示、Root/Shizuku标题下方说明及“断开Root服务”按钮；仍自动显示保存的后端配置。
- Root和Shizuku均使用“申请/重试对应后端连接”的主按钮，同一卡片字体、状态字号和按钮高度保持一致。
- 统一miuix按钮与选择项的字体角色；连接页两个操作按钮使用同一13sp字号和紧凑内边距，并依据实际文字宽度布局。窄屏或大字体放不下时上下排列，不截断文字。
- 删除首页底部“前往连接设置”按钮，保留首次连接步骤。

## 0.3.5 更新

- miuix首页改用0.3.4的Material首页外观：保留旧配色、20dp卡片、排版及按钮形式；miuix其他页面仍使用miuix组件。
- Material 3全页面统一蓝色/中性色彩角色、字号行高、卡片边框、输入框与按钮尺寸。后端和主题选择改为等宽原生分段按钮，其他控件保留Material语义。
- 共享页面边距、卡片内容边距、标签列和控件基线；较大系统字体或窄内容区域下，状态行自动改为上下排列。
- 权限页根据已保存的执行后端直接显示对应配置，不再需要再次点击展开；只展示配置，不自动申请Root/Shizuku授权。
- 证书使用提示放到复制PEM按钮下方，高级设置仍保留HTTP开关与附加名称。
- 旧式启动器资源提供圆角方形和圆形背景；自适应图标保留满幅背景、缩进前景与单色安全区，由系统选择蒙版。

## 0.3.4 更新

- 删除顶部品牌栏和关于入口，关于移至设置页。
- 底部“日志”改为“设置”：可切换miuix / Material 3，立即生效并保存，深浅色跟随系统。日志收进设置页，保留复制和导出。
- 接入固定版本`top.yukonga.miuix.kmp:miuix:0.5.2`，使用真实miuix卡片、按钮、选择项、开关、输入框、导航及对话框。该版本与项目Kotlin 2.2.21/Compose 1.9系列兼容；没有升级到要求更高工具链的新API。
- 设备ID与复制入口移到安全页，删除Root/Shizuku授权卡片底部说明。
- 图标简化为蓝底白色连接链与操作箭头，提供矢量、自适应、圆形、单色和通知版本。

## 0.3.3 更新

- 首页设备状态只保留执行后端、权限状态、实际监听三行；设备ID与复制入口移到连接页。
- Root/Shizuku经实际执行自检成功后，首页显示“对应后端已就绪，执行自检通过”；详细UID仍保留在对应授权面板。
- 权限页点选Root或Shizuku才展开对应授权设置，初始不同时展示两套申请按钮。
- HTTP模式与证书附加名称收进“高级设置”，入口位于复制PEM证书按钮下方。HTTPS仍默认启用。
- 更换青春蓝、白色手机与桥梁组合图标，包含自适应、圆形、单色、通知和应用内图标。

## 0.3.2 更新

- shell始终使用当前执行后端，不再提供独立选择；高级shell默认关闭，需要本地明确开启。升级会移除旧shellBackend配置。
- 连接页按监听IP与端口同行、自动选择/所有接口按钮、Tailscale候选IP、保存按钮、证书区域的顺序排列。
- 自动选择只填写VPN接口内的Tailscale IPv4；无IPv4时保留原值，不回退IPv6或所有接口。列表不显示其他VPN地址或说明。
- 证书SHA256缩略显示，提供完整指纹复制；实际证书、PEM导出和TLS校验仍使用完整内容。

## 0.3.0 保留功能

- 被控端移除“访问地址”设置；只填写监听IP/端口。控制端目标URL由控制端配置。
- HTTPS证书自动包含监听IP和检测到的TailscaleIP；可选MagicDNS/其他IP在高级设置补充，HTTP不要求证书名称。
- shell权限独立开启，执行后端跟随权限页当前选择，不会自行申请Root。
- 辅助IME提供英文/数字/符号按键、大小写、Unicode退格、编辑器动作/换行、切换系统输入法与收起，保留远程Unicode输入。
- 新增自适应/圆形/Android13单色图标，明确桌面和Activity名称资源，修复应用内标题对比度。

## KSU / ReKSU Root 配置

1. 打开 KSU / ReKSU 管理器，在超级用户列表中允许 **AgentBridge**。如使用自定义应用配置，先使用管理器的标准 root 配置。不要关闭 SELinux。
2. AgentBridge → 权限 → 选择 Root →「申请/重试 Root 连接」。在 root 管理器弹窗中允许。
3. 等待页面显示 **Root 已就绪 · UID=0 · 执行自检通过**。
4. 若失败，复制结果或日志。新版分别报告授权 UID、服务连接、执行自检的错误。`UID=-1` 不是 Linux 中读取到的真实 UID，它表示旧版或检测链路没有读到有效结果，不能单凭它判断手机没有 root。

已修正旧版 `newJob()` 未配置输出收集、导致 UID 为空并显示 -1 的直接问题。

新版使用 libsu 6.0.0 的 RootService / AIDL，本地特权服务完成进程执行和二进制输出传输。移除旧版每次操作再次 `su -c`、`setsid` 与外部 `timeout` 的组合；命令超时、输出上限和取消由进程管理器处理。拒绝授权后重试会清理缓存的普通 shell。远端请求不会自行弹出 root 授权。

如果 root 服务仍不兼容当前 ROM，可手动选择并连接官方 Shizuku；实际 UID 会显示，不会静默切换。

## Tailscale：从安装到第一次点击

完整分步说明：[docs/TAILSCALE.md](docs/TAILSCALE.md)。基本流程：

1. 被控手机、运行 Agent 的电脑或手机都打开官方 Tailscale，登录同一 tailnet，确认两设备在线且 ACL 允许 TCP8443。
2. 在 Tailscale 中复制被控手机地址，例如 `100.88.12.34`。
3. AgentBridge → 连接：监听IP填 `100.88.12.34`，端口8443，保留HTTPS，点击「保存连接设置」。也可点「自动选择候选 IP」。没有“访问地址”输入项。
4. 如果监听失败提示地址无法绑定，在连接页明确选择「监听所有接口」并确认，保存后重启服务；仍然用 `https://100.88.12.34:8443` 访问。所有接口可能同时向局域网开放，必须保留鉴权。
5. 连接页复制 PEM 证书，通过可信渠道保存为电脑 `bridge.pem`；安全页批准并复制控制令牌；首页启动服务，可先点「检查本机 API 连接」。

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

同一 Python 会话内读取截图后点击：

```python
import asyncio
from agentbridge import AgentBridge

async def main():
    async with AgentBridge.from_env() as phone:
        shot = await phone.screenshot('screen.png')
        print(shot['width'], shot['height'], shot['imageId'])
        # 先查看截图，再把300、500改成目标位置的实际像素坐标。
        await phone.tap(300, 500)
        await phone.stop()

asyncio.run(main())
```

MCP：在已接入该 tailnet 的电脑上运行 `agentbridge-mcp`（stdio），配置同样环境变量。云端 Agent 若没有加入你的 tailnet，不能直接访问100.x地址；ChatGPT网页本身不自动成为你的Tailscale设备。

## shell 后端怎么设置

权限页选择并连接Root或Shizuku；安全 → 开启“高级shell”。shell直接使用当前执行后端，不再另设后端。切换执行后端或权限变更会停止旧控制会话；截图、点击与输入不需要打开高级shell。

## UI 和卡顿修复

底部导航：首页、权限、连接、安全、设置；统一卡片边距、表单宽度、按钮高度、开关对齐，支持浅色/深色及大字体换行。

旧连接页在组合渲染中读取 Keystore、解密并解析 PKCS12，还每750ms刷新整页。新版通过 ViewModel + StateFlow 事件更新，证书/网络自检在 Dispatchers.IO 执行，证书在内存缓存；输入框编辑不写盘，不触发密钥生成，保存时才验证并持久化。日志只随新记录刷新，不强制滚动。

## 能力与安全

见 [能力表](docs/CAPABILITIES.md)、[API](docs/API.md)、[OpenAPI](docs/openapi.json)、[验证记录](docs/VALIDATION.md)。任意shell默认关闭；中文输入需要用户启用辅助输入法；坐标是最近30秒内截图实际像素，旋转/缩放变化需要重新截图。停止不能撤销已经发生的点击。

所有控制请求需应用令牌，不把100.x地址当身份。HTTPS默认开启，导出的证书必须与访问IP/DNS匹配，SDK使用 `--ca`，不关闭主机名验证。可信网络HTTP为显式高级选项；所有接口监听时局域网连接可能不经过Tailscale加密，优先HTTPS。

## 构建与交付

固定工具链：JDK17、Gradle8.13、AGP8.13.2、Kotlin2.2.21、SDK36 / Build Tools36.0.0。完整Gradle Wrapper包含SHA256，依赖固定版本，无私有路径依赖。

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
sdkmanager 'platforms;android-36' 'build-tools;36.0.0' 'platform-tools'
./scripts/build.sh
pip install -c requirements.lock '.[test]'
pytest -q
```

源码：`android/`、`python/agentbridge/`、`.github/workflows/`、`docs/`；旧公网服务放在 `relay/`，当前不启用。push / PR / 手动运行会构建debug APK；tag release签名设置见 [docs/BUILD.md](docs/BUILD.md)。release仅手动或tag触发，先构建unsigned包，再从指定Secrets取starx密钥签名并核验固定指纹；缺少材料或证书不匹配即失败，不发布替代签名包。详见[签名约定](docs/SIGNING.md)。

当前环境没有KSU/ReKSU真机或实际Tailscale网络。编译、单元测试及静态检查不等同于你的手机实机通过；请依据新页面显示的授权/服务/自检结果验收。
