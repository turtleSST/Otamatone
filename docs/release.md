# 发布与签名

## 构建 Release

Debug 构建不需要私有签名资料。正式包需使用维护者保存的签名身份，或为自己的分发版本配置独立 keystore。

1. 参考根目录的 `signing.properties.example`，将真实配置保存在 `.signing/signing.properties`。
2. 使用 JDK 17 或 21、Android SDK / Build Tools 35。
3. 运行 `./gradlew :app:assembleRelease`，Windows 使用 `gradlew.bat`。

也可用 `-PreleaseSigningProperties=/绝对路径/配置文件` 指定外部配置，或设置 `TADPOLE_STORE_FILE`、`TADPOLE_STORE_PASSWORD`、`TADPOLE_KEY_ALIAS`、`TADPOLE_KEY_PASSWORD` 环境变量。

私钥和密码不得进入 Git。`docs/signing-certificate.sha256` 是公开证书指纹，不包含私钥。安装包更新须与已安装版本的签名身份兼容；遇到签名冲突时，应先备份设置并卸载冲突版本。[Android 签名说明](https://developer.android.com/studio/publish/app-signing)

`setup_release_signing.py` 仅用于尚未建立签名身份的新项目，会拒绝覆盖已有密钥。维护本项目时应恢复原签名备份。Fork 的 Release 应使用自己的 keystore，并相应调整包名和公开证书校验记录。

## 验证

```powershell
New-Item -ItemType Directory -Force verification-output
.\gradlew.bat :app:test :app:lint :app:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest :release-check:assembleDebug 2>&1 | Out-File -Encoding utf8 verification-output/build-log.txt
.\gradlew.bat :app:connectedDebugAndroidTest 2>&1 | Out-File -Encoding utf8 verification-output/debug-device-log.txt
.\scripts\verify_release.ps1 -Serial emulator-5554 -PythonPath python
```

最后一条只用于明确指定的测试模拟器，会替换其中的应用与验证工具。`release-check/` 自带测试依赖，验证实际签名、经过 R8 处理的 APK，无需访问混淆前的应用字段。

```powershell
python scripts/collect_release_verification.py --sdk "$env:LOCALAPPDATA\Android\Sdk" --build-log verification-output/build-log.txt --debug-log verification-output/debug-device-log.txt
```

摘要写入 `docs/verification/release.json`。如已使用本地录音完成音频验证，可追加 `--include-audio`；普通构建与功能验证无需这些录音。

## 发布文件

`releases/<版本>/` 仅保存明确选定的正式 APK、SHA-256 校验文件和版本信息。添加版本时，应核对 APK 与公开证书指纹、更新 `.gitignore` 的 APK 白名单和 README 下载链接，并提交对应源码与标签。

本地完整交付包可在提交后生成：

```powershell
python scripts/package_release.py --sdk "$env:LOCALAPPDATA\Android\Sdk"
```

脚本要求工作区干净，并检查 APK 签名、不可调试标志、ZIP 对齐及实际验证结果。`dist/` 中的源码 ZIP、R8 `mapping.txt` 和本地打包元数据不提交；其中 `mapping.txt` 应由维护者按版本保留，用于还原崩溃栈。

源码 ZIP 由 Git 导出。签名资料、缓存、原始录音和中间日志不属于公共交付内容。
