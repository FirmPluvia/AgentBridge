AgentBridge 0.5 · 连接文案与starx发布约定

- 设置页第二个按钮简化为“连接帮助”，首页连接类型改为“局域网·Tailscale”。
- 版本名保持0.5；版本码14。保留Miuix/Material 3双主题、精简二维码和Agent提示词。
- debug：dev.agentbridge，Android默认调试签名，无自定义signingConfig、不读取签名Secrets。
- release：starx.dev.agentbridge，Gradle先构建unsigned，再由apksigner使用starx别名签名，不核验证书指纹。仅手动/tag触发；缺少任一签名密钥时改用调试密钥签名（产物为debug包），不混用两种签名。
- 辅助输入法组件名适配正式包的新applicationId。两种包独立安装，授权和连接资料需分别配置。

详见SIGNING.md。生产密钥仅在GitHub Secrets中配置；仓库、源码包和日志不包含密钥或口令。GitHub Release附件不再经过固定指纹核验；本地交付下载页仅提供可安装debug包，不宣称已经生成starx正式包。

本地Android构建、两种变体单元测试和lint通过；没有Android真机或模拟器，仍需实际验证Root/Shizuku、输入法及网络连接。签名拒绝路径采用临时测试证书验证，不能当作生产starx密钥验证通过。
