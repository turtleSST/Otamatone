package com.tadpole.instrument.model

import kotlin.math.sqrt

/** Each range is independently measured from low.wav, mid.wav and hi.wav. */
enum class PitchRange(val topHz: Float, val middleHz: Float, val bottomHz: Float) {
    LOW(MeasuredRanges.LOW_TOP,MeasuredRanges.LOW_MIDDLE,MeasuredRanges.LOW_BOTTOM),
    MID(MeasuredRanges.MID_TOP,MeasuredRanges.MID_MIDDLE,MeasuredRanges.MID_BOTTOM),
    HIGH(MeasuredRanges.HIGH_TOP,MeasuredRanges.HIGH_MIDDLE,MeasuredRanges.HIGH_BOTTOM)
}
enum class Smoothing(val milliseconds: Float) { QUICK(2f), BALANCED(4f), SOFT(8f) }

data class PitchCalibration(val topHz: Float, val middleHz: Float, val bottomHz: Float) {
    fun sanitized(): PitchCalibration {
        val top = topHz.safe(20f, 4000f, PitchRange.MID.topHz)
        val bottom = bottomHz.safe(top + 1f, 8000f, (top * 4f).coerceAtMost(8000f))
        val middle = middleHz.safe(top + .001f, bottom - .001f, sqrt(top * bottom))
        return PitchCalibration(top, middle, bottom)
    }
    companion object {
        fun forRange(range: PitchRange) = PitchCalibration(range.topHz, range.middleHz, range.bottomHz)
    }
}

data class InstrumentSettings(
    val range: PitchRange = PitchRange.MID,
    val low: PitchCalibration = PitchCalibration.forRange(PitchRange.LOW),
    val mid: PitchCalibration = PitchCalibration.forRange(PitchRange.MID),
    val high: PitchCalibration = PitchCalibration.forRange(PitchRange.HIGH),
    val smoothing: Smoothing = Smoothing.BALANCED,
    val masterVolume: Float = .78f,
    val bassDb: Float = 0f,
    val trebleDb: Float = 0f,
    val haptic: Boolean = false,
) {
    val calibration: PitchCalibration get() = when (range) { PitchRange.LOW -> low; PitchRange.MID -> mid; PitchRange.HIGH -> high }
    fun withRange(value: PitchRange) = copy(range = value)
    fun withTone(bass: Float,treble: Float) = copy(bassDb=bass,trebleDb=treble).sanitized()
    fun withCalibration(value: PitchCalibration) = when (range) {
        PitchRange.LOW -> copy(low = value.sanitized())
        PitchRange.MID -> copy(mid = value.sanitized())
        PitchRange.HIGH -> copy(high = value.sanitized())
    }
    fun sanitized() = copy(low = low.sanitized(), mid = mid.sanitized(), high = high.sanitized(),
        masterVolume = masterVolume.safe(0f, 1f, .78f),
        bassDb = bassDb.safe(-6f,9f,0f),trebleDb = trebleDb.safe(-6f,9f,0f))
}

private fun Float.safe(low: Float, high: Float, fallback: Float) = if (isFinite()) coerceIn(low, high) else fallback
