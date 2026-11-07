# 构建和发布

工具链固定：JDK17 / Gradle8.13 / AGP8.13.2 / Kotlin2.2.21 / SDK36 / Build Tools36.0.0。Android最低26。Python最低3.11，CI3.12。依赖版本见源码；Gradle Wrapper SHA256固定。

本地 `./scripts/build.sh` 生成默认调试签名的可安装APK，不需要发布密钥。也可用Android Studio打开android目录，选择JDK17，同意SDK许可证后构建。不能只安装没有javac的JRE。

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
sdkmanager 'platforms;android-36' 'build-tools;36.0.0' 'platform-tools'
./scripts/build.sh
pip install -c requirements.lock '.[test]'
pytest -q
```

上传完整源码、Gradle Wrapper JAR及脚本到GitHub仓库；保留gradlew与脚本可执行位。不要上传local.properties、.env、构建缓存及签名材料。`.github/workflows/build.yml`在push、PR及手动触发时运行Python测试、Android单元测试、lint、debug APK构建与签名身份校验，Artifacts中下载APK、SHA256SUMS及报告。

`.github/workflows/release.yml` **只在手动或tag触发**，不在普通push或PR执行。先构建unsigned release，再由独立apksigner步骤使用starx签名并核验固定SHA-256。包名为starx.dev.agentbridge；debug仍为dev.agentbridge，且不配置任何signingConfig、不读取签名Secrets。

需要的Secrets仅为 **KEYSTORE_BASE64、KEYSTORE_PASSWORD、KEY_ALIAS、KEY_PASSWORD**。KEY_ALIAS必须为starx。配置、证书指纹、输出、临时密钥清理和安装迁移见[签名约定](SIGNING.md)。缺少材料或指纹不符时任务失败，没有debug签名或unsigned正式产物回退。

可独立检查unsigned构建（不是可安装正式包）：

```bash
cd android
./gradlew :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
cd ..
python scripts/verify_android_artifact.py --kind unsigned-release --apk android/app/build/outputs/apk/release/app-release-unsigned.apk
```

云环境代理需Java代理配置及受信CA，不使用TrustAll；源码不硬编码本次环境的SDK/JDK私有路径。当前没有用户目标GitHub仓库及写权限，没有推送或远程Actions执行记录。交付zip可上传到自己的仓库。
