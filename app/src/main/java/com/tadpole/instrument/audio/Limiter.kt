package com.tadpole.instrument.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/** Transparent peak gain reduction. Normal levels pass unchanged; no hard clipping. */
class Limiter(sampleRate: Int) {
    private val recovery = (1 - exp(-1.0 / (sampleRate * ReferenceTimbre.limiterRecoveryMs / 1000))).toFloat()
    private var gain = 1f
    fun process(x: Float): Float {
        if (!x.isFinite()) { gain = 1f; return 0f }
        val peak = abs(x)
        gain += (1f - gain) * recovery
        if (peak > ReferenceTimbre.limiterCeiling) gain = min(gain, ReferenceTimbre.limiterCeiling / peak)
        return x * gain
    }
}
