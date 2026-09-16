# 贡献指南

欢迎通过 Issue 反馈问题或提交 Pull Request。

## 问题反馈

请提供设备型号、Android 版本、App 版本和可重复的操作步骤。音频问题请说明档位以及使用手机外放还是耳机。提交日志或截图前，请移除个人信息；不要上传签名密钥、密码或令牌。

## 本地验证

```bash
./gradlew :app:test :app:lint :app:assembleDebug
```

Windows 使用 `gradlew.bat`。触摸、校准或界面修改建议额外运行 `:app:connectedDebugAndroidTest`。Release 优化后的行为可用 `release-check/` 验证，具体步骤见 [发布说明](docs/release.md)。

`MeasuredLow.kt`、`MeasuredMid.kt`、`MeasuredHigh.kt` 和 `MeasuredRanges.kt` 是生成文件。调整音色模型时，应同步更新 `audio-analysis/model/range_model.json`，然后运行 `audio-analysis/generate_range_tables.py`。普通 App 构建和现有单元测试不需要原始录音。

## 文件提交范围

| 应提交 | 不应提交 |
|---|---|
| 应用源码、测试、Gradle Wrapper 与构建配置 | SDK、IDE 状态、本机路径与构建缓存 |
| 公开音色模型和测试参考数据 | 原始录音、参考照片、逐窗口测量缓存 |
| 文档、经过检查的界面截图 | 中间日志、堆转储与临时文件 |
| 明确选定的 Release APK、版本信息与校验文件 | Debug/Test APK、重复源码 ZIP、R8 映射文件 |
| 配置模板和公开签名证书指纹 | 私钥、真实签名配置、密码、访问令牌 |

`.gitignore` 只对白名单中的正式 APK 开放追踪。加入新版本安装包时，需要明确更新白名单并核对哈希。APK 与对应源码应处于同一个发布提交或标签中。

请保持修改范围清晰，说明行为变化及验证结果，并遵循仓库的 [GPL-3.0 许可证](LICENSE)。
