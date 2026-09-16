# 本地录音输入

Android 构建不使用此目录中的录音或照片。公开仓库只保留这份说明，实际输入由 `.gitignore` 排除。

使用自己的录音重新建模时，准备 `audio/low.wav`、`audio/mid.wav`、`audio/hi.wav`：48 kHz、单声道 PCM16，每段三次近似匀速从最高音滑到最低音，保持嘴巴全开、音量固定。

分析步骤见 [音色模型说明](../docs/audio-model.md)。请勿将私人录音或包含个人元数据的参考图片加入提交。
