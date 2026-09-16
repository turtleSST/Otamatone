# 音色分析工具

公开模型位于 `model/range_model.json`。Android 可直接使用源码中的生成数据；从该模型重新生成 Kotlin 文件只需 Python 标准库：

```bash
python audio-analysis/generate_range_tables.py
```

使用自己的录音进行完整分析时：

- `analyze_range_sweeps.py` 读取 `reference/audio/` 的三个 WAV，生成逐窗口测量和模型。
- `prepare_range_validation.py` 从第三次滑音生成验证目标和测试参考数据。
- `RenderRangeFidelity.java` / `compare_ranges.ps1` 驱动 App 的实际 Kotlin DSP 导出 PCM。
- `validate_range_audio.py` / `validate_sweep_render.py` 比较固定音、相对响度和连续滑音。

原始录音和 `model/*_frames.json` 为本地输入与缓存，不提交。控制文件、PCM、图表和报告写入忽略提交的 `output/`。

详细方法和输入要求见 [音色模型说明](../docs/audio-model.md)。
