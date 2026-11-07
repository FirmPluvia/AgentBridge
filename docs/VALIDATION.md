# AgentBridge 0.5 验证记录

日期：2026-11-07。当前版本只面向 **Tailscale/局域网直连 + Root/Shizuku**。ADB模块已删除，APK中没有公网中转客户端。

## 已验证

- Android debug和unsigned release构建；JDK17 / Gradle8.13 / AGP8.13.2 / Kotlin2.2.21 / SDK36 / Build Tools36.0.0。
- Debug APK签名、包名、版本号和min/target SDK静态检查；与0.1.0调试签名一致，可作为同包名升级。
- Android lint无错误（警告保留在报告中）。
- JVM单元测试53项：shell参数特殊字符、超时、输出上限、取消；并发去重和崩溃后不重放；X509 SAN/签名及真实JVM TLS主机名握手；Tailscale地址与通配监听配置；并发证书身份只创建一次；特权IPC二进制帧、错误帧、合计输出上限、负长度拒绝。
- Python独立环境测试（本次计数见下文）。包含保留的旧独立relay协议测试，它们不代表0.2.0 APK启用了公网中转。
- APK名称/图标资源及输入法元数据会执行静态检查；桌面启动器自行隐藏标签的设置无法由App强制覆盖。
- UI源码检查：移除750ms整页刷新；Compose渲染路径不调用Vault/Keystore/PKCS12；证书准备与保存、网络自检在Dispatchers.IO，输入框只更新内存草稿，保存时落配置；增加可并发复用的证书缓存。

最终结果及校验和以交付的 `AgentBridge-verification.zip`、`SHA256SUMS` 为准。

## 0.5 文案与签名策略补丁（版本码14）

- debug实际包名dev.agentbridge、默认Android Debug证书；release实际包名starx.dev.agentbridge且未签名，两个变体均编译、53项JVM单元测试及lint通过。
- 合并后的release Manifest中输入法类仍为dev.agentbridge.BridgeIme，provider authority跟随starx包名；Engine使用实际ComponentName。没有真机输入法切换验收。
- 签名策略测试覆盖缺失Secrets、错误别名、无效Base64、单签名者要求、真实APK包名隔离；使用一次性测试密钥实际签名，确认非指定证书被拒绝、APK不发布、临时密钥清理且原unsigned包未改变。生产starx密钥未在当前环境提供，成功签名路径需GitHub Actions验收。
- Python共36项通过，其中10项签名策略/实际工具检查，既有SDK/协议26项保留。未装Android SDK或未本地构建两种APK时，4项实际工具检查会skip，不能当作已通过。
- 工作流源码检查：debug无signing Secrets，release仅手动/tag，只有签名步骤注入四项指定Secrets，核验后上传；未声称已在用户GitHub运行。
- 界面文案通过源码核对；没有真机/模拟器，未声称视觉或设备操作已通过。

## 0.5 双主题UI与提示词

- Miuix使用固定版本库的实际字体角色、G2卡片/按钮、输入、开关、导航和SuperDialog；删除ClassicMaterialHome及其模式标记。
- Material 3使用Typography层级、surfaceContainer、原生卡片/胶囊按钮/描边输入/勾选分段选择/导航指示器；两套主题均完成编译和lint。
- 源码检查对齐、字体缩放、13sp长连接按钮适配、根层弹窗宿主及二维码截屏允许策略保留；提示词缩短，增加包内设计和初始化说明。
- 构建、lint、既有53项JVM及26项Python协议检查通过。未新增仅复刻样式实现的单元测试，也未把协议测试视为视觉验收。
- adb devices为空，SDK未装模拟器且/dev/kvm不存在。无法进行Android运行时截图；需真机检查两主题的深浅色、大字体、横竖屏、键盘、弹窗和二维码排版。

## 0.4 精简二维码、提示词和帮助

- 原QR编解码测试更新为精简格式2，真实RSA证书编码解码后断言恰好version/deviceId/urls/token/certificatePem五项，无元数据冗余，完整证书/令牌保留。
- 新增7项Python检查（含参数化）：两代导入均保持TLS验证、精简格式缺证书/无效URL/令牌/布尔版本/回环地址拒绝、HTTP须控制端显式允许。
- 源码检查：二维码窗口为SecureOff，无SecureOn；提示词在二维码页展示并可复制；帮助覆盖同Wi-Fi、两类热点、Tailscale；主题标签为Miuix，旧偏好值不变。
- 编译、lint、53项JVM及26项Python检查通过。没有真机，未将截图允许策略、相机扫码、提示词复制、帮助排版或热点互通视为实机已通过。

## 0.3.8 一键自检与完整连接二维码

- 增加7项JVM测试：使用真实RSA证书的QR编码/ZXing解码逐字段比较；缺证书/无效地址拒绝；HTTP导出标志；容量失败；真实有效PNG头尺寸与CRC；截断/错误截图拒绝；UID与后端匹配且命令成功才通过。
- 增加8项Python测试（含参数化）：配置导入后凭证与协议一致、TLS验证及check_hostname保持启用、证书指纹/版本/URL/令牌无效拒绝、HTTP必须显式选择、文件大小限制及环境变量导入。
- UI源码检查：生成仅经风险确认调用，默认QR状态空；只在内存生成，安全Dialog窗口、后台/离页/撤销时清理；自检不修改Engine最近截图引用，不发送点击或文本，不自动授权或开启监听。
- 编译、lint、53项JVM及19项Python检查通过。不把协议/图像编解码测试等同真机相机扫码、触控或Android权限通过。
- 需真机验收：两主题自检过程及取消、QR风险确认、相机扫码后导入、黑白二维码清晰度、旋转和后台隐藏、FLAG_SECURE效果、撤销后旧二维码不可用、真实Root/Shizuku截图与网络检查。

## 0.3.7 miuix弹窗显示层与后端状态

- 核对固定版本miuix 0.5.2发布源码：SuperDialog经DialogLayout注册到LocalDialogStates，需MiuixPopupHost真正渲染；Material Scaffold不包含该宿主。现在根层Box在内容之后挂载唯一宿主，所有miuix页面均可使用。
- 根层使用当前主题onSurface作为文字前景色。保存结果仍由ViewModel消息状态驱动，只有用户关闭时清除，不增加自动超时。
- 新增3项状态回归测试：切换到未连接后端时不显示另一后端缓存、有效连接且UID匹配才展示当前执行后端、服务退出保留失败原因且不继续显示旧就绪详情。
- Android debug/unsigned release、lint及46项JVM/11项Python检查通过；没有实际Compose弹窗点击测试或真机Binder运行验收，不能把源码分析和策略单元测试作为实机已通过。
- 需真机检查：miuix连续保存两次均弹窗；关闭后重新打开关于；监听确认与错误消息可见；深浅色、主题切换及Root/Shizuku授权/退出后的状态正确。

## 0.3.6 权限与连接文案

- 源码检查：移除指定说明、断开Root按钮、首页底部跳转按钮；Root/Shizuku均复用Action主按钮及bodyMedium状态字体。
- 连接动作共用13sp字号、行高和4dp左右内边距，通过Compose文字测量判断同行或上下排列；保留系统字体缩放，不使用省略号。
- Android debug/unsigned release、lint和既有43项JVM/11项Python检查通过。没有真机/模拟器，按钮实际排版、厂商字体和大字号显示仍需实机验收。

## 0.3.5 首页与Material适配

- miuix首页单独采用旧Material外观，其他miuix页面不变；Material分支使用原生分段按钮、OutlinedCard、显式色彩和排版角色。
- 源码检查：所有首页卡片均经过HomeCard；权限面板直接读取runtime.backend（来自持久偏好），无独立展开状态，无进入页面自动授权调用。
- 源码检查：证书帮助位于复制按钮之后、高级入口之前；大字体状态行与标题均有响应式布局分支。
- XML检查：旧式图标方形资源采用圆角、round资源采用圆形，自适应背景仍满幅，前景和monochrome保留安全区。
- Android编译、lint及既有43项JVM/11项Python检查通过。无真机/模拟器，未验证两主题视觉、字体缩放、进程重建后配置显示或OEM启动器蒙版；静态检查不能替代实机验收。

## 0.3.4 设置与双主题

- 核对miuix官方README及Maven Central发布包，固定0.5.2；该包使用Kotlin 2.2.20，项目使用2.2.21。没有动态版本。
- 两套核心组件代码均完成编译、lint。主题选择保存在本地偏好，切换不修改执行权限或控制会话。
- 静态检查顶栏删除、设备ID仅安全页显示、授权底部说明删除、设置中的日志/关于入口及主题组件分支。
- 没有Android真机、模拟器、UI自动化环境。主题实时切换、进程重启后恢复、深浅色、软键盘、对话框及不同字体缩放尚未实机验收；不能以编译代替视觉通过。

## 0.3.3 状态与权限界面

- 首页三行、设备ID移位、授权面板按需展开、证书高级项位置由源码检查；尚无真机视觉验收。
- Shizuku在后台通过本地UserService执行id -u，核对实际命令UID与服务UID后才标记自检通过；服务退出会清除就绪状态。
- 新增4项执行自检策略测试：root/shell真实结果、UID不一致拒绝、普通App UID拒绝、失败/缺失输出拒绝。这些测试不代表真实Shizuku Binder运行验收。
- 新图标使用原创生成设计并转为Android矢量；静态检查启动器图标与单色资源，尚未在各ROM启动器实机验收。

## 0.3.2 连接与shell简化

- shell测试覆盖默认关闭、当前Root/Shizuku直接授权策略、未选择/移除的ADB等后端拒绝。
- 新增自动选择IPv4优先及IPv6-only不回退测试、证书缩略显示不改变原指纹测试。
- 连接页重排及仅显示Tailscale候选由源码检查；没有真机布局或键盘遮挡验收。

## 0.3.1 VPN 地址过滤

新增6项策略测试：物理网络CGNAT和Wi-Fi排除、接口名称不冒充VPN、双栈可用地址过滤、仅精确VPN接口回退、其他VPN不冒称Tailscale、系统隐藏VPN地址时不补用物理网络地址。Android读取使用公开ConnectivityManager/NetworkCapabilities/LinkProperties API；没有真实Android VPN环境，策略测试不等同实际Tailscale接口验证。

## 0.3.0 保留的修改与回归

1. 删除被控端“访问地址”字段和必填校验，更新配置模型、迁移和Tailscale文档。通配监听无需填写控制端目标。
2. 证书自动包含监听IP、检测到的TailscaleIP，手动附加名称仅作为可选高级项。新增无访问地址配置及Tailscale地址范围过滤测试。
3. 旧版shell独立选择已由0.3.2的跟随执行后端策略替换。
4. 新辅助键盘支持英文/数字/符号、大小写、Unicode退格、长按删除、编辑器Next/Done/Send等动作与多行换行；不记录输入内容；手动中文通过切换系统输入法。
5. 表单键盘Next明确聚焦端口，Done隐藏键盘；输入时收起底部导航。
6. 新启动/自适应/圆形/单色/通知图标；桌面与Activity显式引用同一app_name资源；顶部名称使用明确前景色。

Root保留0.2.0对libsu newJob默认不收集输出而导致UID=-1的修复，以及RootService/AIDL、权限失败重试和二进制执行通道。没有再次加入ADB或公网客户端。

## 尚未验证，需真机验收

| 项目 | 需用户验证 |
|---|---|
| KSU / ReKSU | 管理器允许AgentBridge，点击重试，确认服务自检UID0，截图及点击实际成功 |
| UI流畅度 | 反复进入连接页、输入地址、保存证书、开关深浅色；本次未记录真机帧时间或ANR跟踪 |
| 布局视觉 | 新增辅助键盘、自适应图标和明确名称；尚未在不同屏幕、字体缩放、启动器与深浅色上实机截图验收 |
| Tailscale跨网络 | 两端同tailnet且ACL允许8443，手机启动服务，另一设备完成status/screenshot/tap |
| Android VPN绑定 | 优先绑定手机TailscaleIP；若ROM不允许，明确选择所有接口并验证实际监听范围 |
| 中文输入/旋转 | 辅助IME输入中文多行并恢复；旧截图在旋转后被拒绝，新截图坐标正确 |
| Root/Shizuku退出 | 撤销授权、杀服务、Binder死亡后真实不可用，不自动切换权限 |
| 断网/去重/停止 | 不重复点击；排队任务取消；已经发生的副作用不可撤销 |
| GitHub Actions | 本地工作流文件已更新，但没有在用户GitHub仓库执行或发布 |

## 限制

- 没有Android真机、KSU/ReKSU运行环境或实际tailnet，不把JVM或模拟协议测试当实机通过。
- APK当前不使用公网中转；relay/SDK旧服务代码仍可独立阅读，不能用它路由到本版APK。
- 只支持默认显示屏；ROM输出、保护页面、安全键盘可能限制操作。有效黑PNG无法通用可靠区分FLAG_SECURE与普通黑屏。
- 取消请求会终止受控主命令；主动脱离/守护化的子进程不能保证全部终止，已发生的点击或输入不能撤销。
- Shell超时等异常可能丢失部分输出；exitCode未知时为null，明确unknown，不伪造成功。
- 直连仍为一个共享高熵控制令牌；多个持有者没有独立身份隔离。单一UI会话占用及高级shell默认关闭。
- TLS证书需明确导出到控制端，控制端目标IP/域名须匹配SAN；没有TrustAll或关闭主机名校验。
- 系统Doze、进程终止、VPN退出与厂商限制可能使服务离线；开机自动启动默认关闭，不承诺永久在线。
