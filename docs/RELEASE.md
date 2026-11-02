AgentBridge 0.3.7 · miuix提示与执行后端状态

- 显式挂载miuix根层弹窗宿主，修复Material Scaffold下保存结果等提示不显示。
- 对话框文字随当前深浅色主题显示，操作结果不自动消失。
- 当前选择后端有有效IPC连接及UID时显示当前执行后端；否则显示Root未授权或Shizuku未授权，并保留进度/错误详情。
- 切换后端不引用另一后端缓存，连接失效不展示旧就绪详情。

同调试签名可覆盖升级。编译、lint、46项JVM及11项Python检查通过；miuix弹窗及真机权限交互仍需实机验证。见VALIDATION.md。
