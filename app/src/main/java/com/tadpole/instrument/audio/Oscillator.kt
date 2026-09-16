package com.tadpole.instrument.audio

/** One continuous phase, including during pitch-anchor interpolation. */
class Oscillator(private val bank: WavetableBank,private val sampleRate: Int) {
    private var phase=0.0
    fun next(frequency: Float,low: Float=0f,mid: Float=1f,high: Float=0f): Float {
        var signal=0f
        if(low>0f) signal+=low*bank.sample(phase,frequency,sampleRate,0)
        if(mid>0f) signal+=mid*bank.sample(phase,frequency,sampleRate,1)
        if(high>0f) signal+=high*bank.sample(phase,frequency,sampleRate,2)
        phase+=frequency/sampleRate
        if(phase>=1.0) phase-=1.0
        return signal
    }
}
