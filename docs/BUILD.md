# 构建和发布

工具链固定：JDK17 / Gradle8.13 / AGP8.13.2 / Kotlin2.2.21 / SDK36 / Build Tools36.0.0。Android最低26。Python最低3.11，CI3.12。依赖版本见源码；Gradle Wrapper SHA256防止错误分发包。

本地 `./scripts/build.sh` 生成可安装调试签名 APK，不需要发布密钥。也可用 Android Studio 打开 android 目录，选择 JDK17，同意SDK许可证后构建。不能使用只有JRE没有javac的安装。

GitHub仓库根目录包含 `.github/workflows/build.yml` 和 `release.yml`。上传全部源码、Wrapper JAR及脚本（可执行位）；不要上传 `local.properties`、`.env`、构建缓存及签名文件。push、pull_request、workflow_dispatch 运行Python测试、Android单元测试、lint、debug APK构建，Artifacts中下载APK、SHA256SUMS和报告。

推送 `v*` tag 触发 Release。正式签名先在可信本地生成私有 keystore，例如 Android Studio Generate Signed Bundle / APK，保管恢复副本，然后在 GitHub Repository → Settings → Secrets and variables → Actions 设置：

| Secret | 值 |
|---|---|
| AB_KEYSTORE_BASE64 | keystore文件完整Base64（Linux `base64 -w0 release.jks`，不要提交输出） |
| AB_STORE_PASSWORD | keystore密码 |
| AB_KEY_ALIAS | 签名alias |
| AB_KEY_PASSWORD | 私钥密码 |

Workflow仅在四项齐全时把key写入受限临时文件，构建后始终删除。Gradle从环境读取秘密，不打印其值。缺少任一项生成明确命名的 `AgentBridge-release-UNSIGNED.apk`，未签名产物不能直接安装。debug产物始终明确标识debug。不要用调试签名伪装正式发布；正式签名与debug不同，覆盖安装可能需要先卸载（卸载会丢失设备身份及授权）。

本地发布可在安全环境设置 `AB_SIGNING_FILE`（绝对路径）、`AB_STORE_PASSWORD`、`AB_KEY_ALIAS`、`AB_KEY_PASSWORD`，运行 `./gradlew :app:assembleRelease :app:lintRelease`。不要在shell历史放明文密码。

云环境的HTTP代理需要Java单独配置代理与受信CA。应把环境提供的CA加入JDK truststore；不要使用TrustAll。交付源码不硬编码本次环境代理、SDK或JDK的私有路径。

本次没有GitHub写入凭证和目标仓库，没有执行推送/远程Actions。交付zip可以解压后上传到自己的仓库。
