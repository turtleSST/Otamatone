package com.tadpole.instrument.audio

import com.tadpole.instrument.model.InstrumentSettings
import kotlin.math.abs
import kotlin.math.exp

/** Pure Kotlin DSP, also executed directly by JVM tests and offline WAV rendering. */
class SynthEngine(val sampleRate: Int,bank: WavetableBank = WavetableBank()) {
    private val oscillator=Oscillator(bank,sampleRate)
    private val envelope=Envelope(sampleRate)
    private val limiter=Limiter(sampleRate)
    private val bass=ShelfFilter(sampleRate,ReferenceTimbre.bassShelfHz,false)
    private val treble=ShelfFilter(sampleRate,ReferenceTimbre.trebleShelfHz,true)
    private var settings: InstrumentSettings?=null
    private var pitchCoefficient=coefficient(4f)
    private val gainCoefficient=coefficient(ReferenceTimbre.gainSmoothingMs)
    private val mouthCoefficient=coefficient(ReferenceTimbre.mouthSmoothingMs)
    private var volume=.78f
    private val rangeWeights=floatArrayOf(0f,1f,0f)
    private val rangeStep=1f/(sampleRate*.012f)
    var currentFrequency=461.51923f; private set
    var currentMouthOpen=0f; private set
    var peak=0f; private set
    val isSilent get()=envelope.silent

    private fun coefficient(ms: Float)=(1-exp(-1.0/(sampleRate*ms/1000))).toFloat()

    /** No allocation, locks, logs, UI access, or I/O in this function. */
    fun render(output: FloatArray,count: Int,parameters: AudioParameters,forceRelease: Boolean=false) {
        val s=parameters.settings
        if(settings!==s) {
            val first=settings==null
            if(first) {
                volume=s.masterVolume
                for(r in 0..2) rangeWeights[r]=if(r==s.range.ordinal) 1f else 0f
            }
            val fade=if(first) 0 else (sampleRate*.012f).toInt()
            if(first || settings?.bassDb!=s.bassDb) bass.setGain(s.bassDb,fade)
            if(first || settings?.trebleDb!=s.trebleDb) treble.setGain(s.trebleDb,fade)
            settings=s;pitchCoefficient=coefficient(s.smoothing.milliseconds)
        }
        val rawTarget=parameters.targetFrequency
        val target=if(rawTarget.isFinite()) rawTarget.coerceIn(20f,minOf(8000f,sampleRate*.42f)) else 440f
        val gate=parameters.gate && parameters.enabled && !forceRelease
        val rawMouth=parameters.targetMouthOpen
        val mouthTarget=if(rawMouth.isFinite()) rawMouth.coerceIn(0f,1f) else 0f
        // No portamento from the previous note after full silence; retriggers retain continuity.
        if(envelope.silent) currentFrequency=target
        peak=0f
        var i=0
        while(i<count) {
            currentFrequency+=(target-currentFrequency)*pitchCoefficient
            volume+=(s.masterVolume-volume)*gainCoefficient
            currentMouthOpen+=(mouthTarget-currentMouthOpen)*mouthCoefficient
            val mouthGain=ReferenceTimbre.closedMouthGain+
                (ReferenceTimbre.openMouthGain-ReferenceTimbre.closedMouthGain)*currentMouthOpen
            // A finite fade handles another range change before the first finishes.
            val selected=s.range.ordinal
            val before=rangeWeights[selected]
            if(before<1f) {
                val after=minOf(1f,before+rangeStep)
                val keep=(1f-after)/(1f-before)
                for(r in 0..2) rangeWeights[r]=if(r==selected) after else rangeWeights[r]*keep
            }
            var sample=oscillator.next(currentFrequency,rangeWeights[0],rangeWeights[1],rangeWeights[2])
            sample=treble.process(bass.process(sample))
            sample=limiter.process(sample*envelope.next(gate)*volume*mouthGain)
            output[i]=sample
            if(abs(sample)>peak) peak=abs(sample)
            i++
        }
    }
}
