# 0.2.0 权限能力表

Android端只保留 Root / Shizuku；ADB及其配对、传统TCP和原生库已移除。只有Tailscale/局域网直连HTTP(S)/WebSocket，没有公网中转入口。

| 能力 | Root | Shizuku |
|---|---|---|
| 身份 | libsu授权shell检查 + RootService实际UID0 + IPC `id -u` 自检 | 官方UserService / AIDL，展示实际UID0或2000 |
| 截图 | `screencap -p`原始二进制，经文件描述符和长度帧返回 | 同左，受shell/root实际权限限制 |
| 点击、长按、滑动、系统键 | `input`，图像坐标校验 | 同左 |
| 中文、多行与特殊字符 | 用户主动启用辅助IME，临时切换并恢复 | 同左 |
| UI hierarchy / 定位 | uiautomator，自绘或保护页面可不支持 | 同左 |
| 前台应用 | dumpsys，ROM解析失败明确返回 | 同左 |
| Shell | 单独高级权限，默认关闭，跟随当前执行后端 | 同左，不把shell UID2000当root |
| 退出与拒绝 | Binder死亡、授权失败、UID异常明确显示；拒绝后可重新检测 | 监听Binder死亡和授权回调，无静默切换 |

Root不再为每条命令重新调用su，也不依赖外部setsid/timeout。libsu RootService建立受控本地Binder通道；Java进程管理负责超时、输出上限和取消。主命令可以终止，主动脱离或守护化的后代进程不保证能全部撤销，已发生的点击不可逆。

实际网络API与执行后端分离。未授权时capabilities.available=false，不能把入口存在当成功保证。远端不会自动申请root；需要用户先在权限页授权并连接。

最低Android8/API26，target36。没有强制MediaProjection或无障碍。没有绕过锁屏、FLAG_SECURE、DRM或安全键盘。仅支持默认显示屏；ROM显示旋转报告差异可能返回UNSUPPORTED。

0.2.0仍使用Bouncy Castle生成TLS证书、libsu和Shizuku；不再带入Conscrypt/SPAKE2/LibADB原生库。实际ABI和大小见新验证报告。
