# 0.5 签名与包名约定

版本名保持 **0.5**，本次版本码 **14**。公开的证书指纹、别名与包名可存入仓库；私钥、密钥库文件、口令、Base64不可以。

| 构建 | applicationId | 签名 | 触发 |
|---|---|---|---|
| debug | dev.agentbridge | Android插件的默认调试签名；不配置任何signingConfig | push / pull_request / workflow_dispatch |
| release | starx.dev.agentbridge | 先构建unsigned，再apksigner签名；四项Secrets齐全时只接受指定starx证书，缺任一项时改用标准Android调试密钥签名、不做证书校验 | workflow_dispatch / tag push |

Gradle两种构建都不读取签名环境变量，不配置自定义signingConfig。namespace和Kotlin类名仍为dev.agentbridge；辅助输入法使用实际ComponentName，不假设类名随applicationId移动。

## 配置GitHub Secrets

在仓库 Settings → Secrets and variables → Actions → New repository secret 中私下填写：

| Secret | 内容 |
|---|---|
| KEYSTORE_BASE64 | **现有starx密钥库文件**的完整Base64 |
| KEYSTORE_PASSWORD | 密钥库口令 |
| KEY_ALIAS | 固定值starx |
| KEY_PASSWORD | starx私钥口令 |

Base64请在可信环境生成并直接粘贴到Secrets界面，避免终端记录、命令历史、CI日志及聊天记录。不要创建新密钥冒充既有starx身份。不要将密钥文件、口令或Base64写进仓库、配置文件、issue或源码包。文件扩展名过滤不能替代人工确认。

指定证书SHA-256：

```text
E7:D1:77:11:20:91:50:25:19:99:E5:DE:B2:2A:B3:65:C8:26:D7:5A:0D:23:0B:DC:1F:44:0D:8F:34:23:17:0E
```

## Release流程

1. GitHub Actions选择 **Starx release → Run workflow**，或推送一个tag。普通分支push及PR不会运行release任务。
2. Gradle构建、单元测试、lint，生成 `android/app/build/outputs/apk/release/app-release-unsigned.apk`。此阶段没有注入签名Secrets。
3. `verify_android_artifact.py`确认starx包名及未签名状态。
4. 仅签名步骤注入上述四项Secrets。`scripts/sign_release.py`校验别名，在Runner临时目录（源码仓库之外）创建0700目录与0600密钥库，调用apksigner；口令用env参数传递，不放进命令行参数。
5. 四项Secrets齐全时，签名后调用 **apksigner verify --verbose --print-certs**，必须只有一个签名者，证书SHA-256逐字节匹配上述值。工具原始诊断被捕获，不回显；只发布经校验的公开身份JSON、APK与SHA256SUMS。
6. 通过后才将 `AgentBridge-v0.5-starx-release.apk` 移入release-artifacts并上传；tag同时附加到GitHub Release。私钥临时文件无论成功失败均清理。

缺少任一Secret时不再中止任务，而是改用标准Android调试密钥（keytool现场生成，android/android）签名，**不做证书校验、不发布RELEASE-VERIFICATION.json**，产物为 `AgentBridge-v0.5-debug-release.apk`，Artifact与Release标题会标明debug。调试签名的release包名仍是starx.dev.agentbridge，但签名与starx证书不同，**不能覆盖安装starx签名的包，反之亦然**。别名不符、口令错误、证书不符或签名工具失败仍会中止任务。**不上传unsigned包冒充正式包。** 本地构建的unsigned包只供检查，不能直接安装。

## Debug流程

运行 `./scripts/build.sh` 或Build and verify工作流。保持原包名，AGP使用默认Android调试密钥；debug任务没有签名Secrets引用，不读取四个签名Secrets。签后验证包名及Android Debug证书身份。调试密钥可能因构建机器不同而变化，因此跨机器产物不保证覆盖安装；正式签名不受此影响。

## 两种安装互相独立

release包名新增starx前缀，不能作为原debug应用的覆盖更新；两者可同时安装，各有设备ID、令牌、证书、Root/Shizuku授权及输入法启用状态。请重新配置正式包并更新控制端连接资料。不要同时让两个应用监听同一地址及8443端口；停止其中一个或明确使用不同端口。

## 本次验证边界

已本地编译并验证默认debug包与starx包名unsigned release。测试使用临时测试密钥实际签名，确认错误证书被拒绝、无可发布输出且临时密钥删除；**测试密钥不是starx生产密钥**。当前环境没有生产Secrets，没有生成或验证真实starx签名包，也未在用户GitHub仓库运行Actions。
