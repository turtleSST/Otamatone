package com.tadpole.instrument.model

import android.content.Context
import androidx.core.content.edit
import kotlin.math.abs
import kotlin.math.sqrt

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("instrument_settings", Context.MODE_PRIVATE)
    fun load(): InstrumentSettings = runCatching {
        val schema = prefs.getInt("schema", 1)
        val migrated = schema >= 2
        val oldTonePreset = schema<4 && prefs.getFloat("bassDb",3f)==3f && prefs.getFloat("trebleDb",2f)==2f
        val settings = InstrumentSettings(
            range = if (migrated) PitchRange.valueOf(prefs.getString("range", "MID")!!) else PitchRange.MID,
            low = readCalibration(PitchRange.LOW, schema),
            mid = readCalibration(PitchRange.MID, schema),
            high = readCalibration(PitchRange.HIGH, schema),
            smoothing = if (migrated) Smoothing.valueOf(prefs.getString("smoothing", "BALANCED")!!) else Smoothing.BALANCED,
            masterVolume = prefs.getFloat("volume", .78f),
            bassDb = if(oldTonePreset) 0f else prefs.getFloat("bassDb",0f),
            trebleDb = if(oldTonePreset) 0f else prefs.getFloat("trebleDb",0f),
            haptic = prefs.getBoolean("haptic", false),
        ).sanitized()
        if (schema<4) save(settings)
        settings
    }.getOrDefault(InstrumentSettings())

    private fun readCalibration(range: PitchRange, schema: Int): PitchCalibration {
        val d = PitchCalibration.forRange(range)
        if (schema<2) return d
        val prefix = range.name
        val saved = PitchCalibration(prefs.getFloat("${prefix}_top", d.topHz),
            prefs.getFloat("${prefix}_middle", d.middleHz), prefs.getFloat("${prefix}_bottom", d.bottomHz))
        if(schema<4) {
            val scale=when(range) {PitchRange.LOW->.5f;PitchRange.MID->1f;PitchRange.HIGH->2f}
            val oldTop=213f*scale;val oldBottom=1000f*scale
            if(abs(saved.topHz-oldTop)<.001f && abs(saved.middleHz-sqrt(oldTop*oldBottom))<.002f &&
                abs(saved.bottomHz-oldBottom)<.001f) return d
        }
        return saved
    }

    fun save(s: InstrumentSettings) {
        prefs.edit {
            putInt("schema", 4).putString("range", s.range.name)
                .putString("smoothing", s.smoothing.name).putFloat("volume", s.masterVolume)
                .putBoolean("haptic", s.haptic).putFloat("bassDb",s.bassDb).putFloat("trebleDb",s.trebleDb)
            for (range in PitchRange.entries) {
                val c = s.withRange(range).calibration
                putFloat("${range.name}_top", c.topHz).putFloat("${range.name}_middle", c.middleHz)
                    .putFloat("${range.name}_bottom", c.bottomHz)
            }
            remove("mouth").remove("waveform").remove("vibrato").remove("control").remove("minimum").remove("octaves")
        }
    }
}
