package com.tadpole.instrument.audio

import com.tadpole.instrument.model.InstrumentSettings

/** UI writes primitive control targets; audio thread never touches Compose state. */
class AudioParameters {
    @Volatile var settings = InstrumentSettings()
    @Volatile var targetFrequency = settings.calibration.middleHz
    @Volatile var gate = false
    @Volatile var enabled = false
    @Volatile var touchNormalized = .5f
    @Volatile var targetMouthOpen = 0f
}

class AudioDiagnostics {
    @Volatile var frequency = InstrumentSettings().calibration.middleHz
    @Volatile var mouthOpen = 0f
    @Volatile var sampleRate = 0
    @Volatile var burstFrames = 0
    @Volatile var nativeBurstFrames = 0
    @Volatile var bufferFrames = 0
    @Volatile var capacityFrames = 0
    @Volatile var underruns = 0
    @Volatile var lowLatency = false
    @Volatile var queuedMilliseconds = -1f
    @Volatile var renderLoad = 0f
    @Volatile var peak = 0f
    @Volatile var status = "正在准备音色"
    @Volatile var running = false
    @Volatile var error = false
}
