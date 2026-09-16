package com.tadpole.instrument.audio

/** Finite smoothstep ramps: exact silence after release, retrigger from current level. */
class Envelope(sampleRate: Int) {
    private val attack = (sampleRate * ReferenceTimbre.attackMs / 1000).toInt().coerceAtLeast(1)
    private val release = (sampleRate * ReferenceTimbre.releaseMs / 1000).toInt().coerceAtLeast(1)
    private var oldGate = false
    private var start = 0f
    private var destination = 0f
    private var elapsed = 0
    private var duration = 1
    var value = 0f
        private set
    val silent get() = value == 0f && !oldGate

    fun next(gate: Boolean): Float {
        if (gate != oldGate) {
            oldGate = gate
            start = value
            destination = if (gate) 1f else 0f
            elapsed = 0
            duration = if (gate) attack else release
        }
        if (elapsed < duration) {
            val t = (++elapsed).toFloat() / duration
            val shaped = t * t * (3 - 2 * t)
            value = start + (destination - start) * shaped
        } else value = destination
        return value
    }
}
