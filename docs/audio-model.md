# 音色模型

音色通过三档录音标定，运行时使用生成的带限波表，而不是播放录音。公开仓库包含 `model/range_model.json`、Kotlin 生成数据以及单元测试参考数据；原始 WAV、参考照片和逐窗口测量缓存不随仓库分发。

## 音色与音域

LOW / MID / HIGH 分别使用 60 / 59 / 61 个音色锚点，谐波容器上限为 384 / 128 / 32。一个公共输出增益保留各音高的相对响度变化。波表在 AudioTrack 播放前构建，实时渲染中不读取录音或分配波表。

| 档位 | 上端低音 | 中点 | 下端高音 |
|---|---:|---:|---:|
| LOW | 54.49 Hz | 98.74 Hz | 292.18 Hz |
| MID | 203.79 Hz | 388.37 Hz | 1057.64 Hz |
| HIGH | 806.26 Hz | 1446.46 Hz | 4443.68 Hz |

默认位置曲线由三次近似匀速滑弦估计。手动三点校准可覆盖当前档位，音名标记根据实际使用的曲线计算。头部只改变音量，闭合与张开增益为 0.65 / 1.0。

## 生成 Kotlin 数据

Android 构建不依赖 Python。需要从公开模型重新生成 Kotlin 数据时运行：

```bash
python audio-analysis/generate_range_tables.py
```

此步骤只使用 Python 标准库和已提交的模型文件。生成结果位于 `app/src/main/java/com/tadpole/instrument/audio/Measured*.kt` 和 `model/MeasuredRanges.kt`。

## 使用自己的录音重新建模

安装 `audio-analysis/requirements.txt` 中的依赖，并在本地准备 `reference/audio/low.wav`、`mid.wav`、`hi.wav`。每段应为 48 kHz 单声道 PCM16，包含三次近似匀速的最高音到最低音滑弦，保持嘴巴打开和音量固定。

```powershell
python audio-analysis/analyze_range_sweeps.py
python audio-analysis/generate_range_tables.py
.\audio-analysis\compare_ranges.ps1 -PythonPath python
python audio-analysis/validate_sweep_render.py
```

流程先跟踪基频并校正八度误判，再对滑音作局部相位解调，按完整周期提取谐波。前两次滑音用于拟合，第三次用于留出验证。录音及逐窗口测量默认忽略提交；控制 CSV、PCM、图表和验证结果写入 `audio-analysis/output/`。

## 验证范围

当前模型的 384 个留出固定音窗口平均加权谐波误差约为 LOW 1.71 dB、MID 1.25 dB、HIGH 1.06 dB；连续滑音另复测了 129 个窗口。指标覆盖 16 kHz 内、振幅高于最强谐波 −35 dB 的成分，不能换算为主观相似百分比。

这些结果对应标定录音。录音包含乐器、房间和麦克风响应，手机外放还会受到扬声器影响。公开的单元测试参考数据可用于回归检查，完整录音分析需要自行提供上述本地输入。
