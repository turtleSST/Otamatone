package com.tadpole.instrument.audio

/** Voicing controls; separate measured LOW/MID/HIGH harmonics live in Measured*.kt. */
object ReferenceTimbre {
    const val attackMs = 8f
    const val releaseMs = 28f
    const val gainSmoothingMs = 12f
    // One global multiplier preserves measured level differences across notes/ranges.
    const val measuredGain = MeasuredLow.outputGain
    const val closedMouthGain = .65f
    const val openMouthGain = 1f
    const val mouthSmoothingMs = 8f
    const val bassShelfHz = 600f
    const val trebleShelfHz = 4500f
    const val limiterCeiling = .94f
    const val limiterRecoveryMs = 80f
    // Head motion controls gain only. Shelves are static, user-adjustable output
    // compensation; neither a vowel filter nor a replacement oscillator.
}
