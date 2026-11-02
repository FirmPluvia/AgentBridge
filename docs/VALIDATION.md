# AgentBridge 0.3.7 验证记录

日期：2026-11-02。当前版本只面向 **Tailscale/局域网直连 + Root/Shizuku**。ADB模块已删除，APK中没有公网中转客户端。

## 已验证

- Android debug和unsigned release构建；JDK17 / Gradle8.13 / AGP8.13.2 / Kotlin2.2.21 / SDK36 / Build Tools36.0.0。
- Debug APK签名、包名、版本号和min/target SDK静态检查；与0.1.0调试签名一致，可作为同包名升级。
- Android lint无错误（警告保留在报告中）。
- JVM单元测试46项：shell参数特殊字符、超时、输出上限、取消；并发去重和崩溃后不重放；X509 SAN/签名及真实JVM TLS主机名握手；Tailscale地址与通配监听配置；并发证书身份只创建一次；特权IPC二进制帧、错误帧、合计输出上限、负长度拒绝。
- Python独立环境11项测试。包含保留的旧独立relay协议测试，它们不代表0.2.0 APK启用了公网中转。
- APK名称/图标资源及输入法元数据会执行静态检查；桌面启动器自行隐藏标签的设置无法由App强制覆盖。
- UI源码检查：移除750ms整页刷新；Compose渲染路径不调用Vault/Keystore/PKCS12；证书准备与保存、网络自检在Dispatchers.IO，输入框只更新内存草稿，保存时落配置；增加可并发复用的证书缓存。

最终结果及校验和以交付的 `AgentBridge-verification.zip`、`SHA256SUMS` 为准。

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
