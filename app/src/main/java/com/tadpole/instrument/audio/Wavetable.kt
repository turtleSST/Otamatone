package com.tadpole.instrument.audio

import kotlin.math.*

/** Three independent measured voices. Constructed once off the UI/audio callback.
 * PCM16 tables use a per-table scale: compression never normalizes note loudness. */
class WavetableBank {
    companion object { const val SIZE = 2048 }
    private class Table(val samples: ShortArray, val scale: Float)
    private class Voice(val frequencies: FloatArray, val limits: IntArray, val anchors: Array<Array<Table>>)
    private val voices: Array<Voice>

    init {
        // Temporary bases are released after construction; retain only compact tables.
        val cosine=Array(384) { h -> FloatArray(SIZE) { cos(2*PI*(h+1)*it/SIZE).toFloat() } }
        val sine=Array(384) { h -> FloatArray(SIZE) { sin(2*PI*(h+1)*it/SIZE).toFloat() } }
        voices=arrayOf(
            buildVoice(MeasuredLow.frequencies,MeasuredLow.amplitudes,MeasuredLow.phases,cosine,sine),
            buildVoice(MeasuredMid.frequencies,MeasuredMid.amplitudes,MeasuredMid.phases,cosine,sine),
            buildVoice(MeasuredHigh.frequencies,MeasuredHigh.amplitudes,MeasuredHigh.phases,cosine,sine),
        )
    }

    private fun buildVoice(frequencies: FloatArray, amplitudes: Array<FloatArray>, phases: Array<FloatArray>,
        cosine: Array<FloatArray>,sine: Array<FloatArray>): Voice {
        val maximum=amplitudes[0].size
        val limits=((1..24).toList()+(28..64 step 4)+(72..128 step 8)+(144..256 step 16)+
            (288..384 step 32)).filter { it<=maximum }.toIntArray()
        val anchors=Array(frequencies.size) { anchor ->
            val cumulative=FloatArray(SIZE+1)
            var harmonic=0
            var previous: Table?=null
            Array(limits.size) { level ->
                var changed=false
                while(harmonic<limits[level]) {
                    val amplitude=amplitudes[anchor][harmonic]*ReferenceTimbre.measuredGain
                    if(amplitude!=0f) {
                        val c=cos(phases[anchor][harmonic]);val s=sin(phases[anchor][harmonic])
                        var i=0
                        while(i<SIZE) {cumulative[i]+=amplitude*(cosine[harmonic][i]*c-sine[harmonic][i]*s);i++}
                        changed=true
                    }
                    harmonic++
                }
                if(!changed && previous!=null) previous!! else {
                    cumulative[SIZE]=cumulative[0]
                    var peak=0f
                    for(v in cumulative) peak=max(peak,abs(v))
                    val scale=max(peak/32767f,1e-12f)
                    Table(ShortArray(SIZE+1) {(cumulative[it]/scale).roundToInt().coerceIn(-32767,32767).toShort()},scale)
                        .also {previous=it}
                }
            }
        }
        return Voice(frequencies,limits,anchors)
    }

    private fun lookup(t: Table,index: Int,fraction: Float): Float {
        val a=t.samples[index].toFloat()
        return (a+(t.samples[index+1]-a)*fraction)*t.scale
    }
    private fun band(tables: Array<Table>,level: Int,blend: Float,index: Int,fraction: Float): Float {
        val rich=lookup(tables[level],index,fraction)
        if(level==0) return rich
        val lean=lookup(tables[level-1],index,fraction)
        return lean+(rich-lean)*blend
    }

    /** No allocation. Every included harmonic is below 0.45 * sampleRate. */
    fun sample(phase: Double,frequency: Float,sampleRate: Int,range: Int=1): Float {
        val voice=voices[range];val limits=voice.limits
        val allowed=.45f*sampleRate/frequency
        var level=0
        while(level+1<limits.size && limits[level+1]<=allowed) level++
        val next=if(level+1<limits.size) limits[level+1] else limits[level]+8
        val fadeWidth=minOf(limits[level]*.06f,(next-limits[level]).toFloat())
        val blend=((allowed-limits[level])/fadeWidth).coerceIn(0f,1f)
        val position=(phase*SIZE).toFloat().coerceAtMost(SIZE-.0002f)
        val index=position.toInt();val fraction=position-index
        val frequencies=voice.frequencies
        var lo=0;var hi=frequencies.lastIndex
        while(lo<hi) {val mid=(lo+hi+1)/2;if(frequencies[mid]<=frequency) lo=mid else hi=mid-1}
        val a=band(voice.anchors[lo],level,blend,index,fraction)
        if(lo==frequencies.lastIndex || frequency<=frequencies[0]) return a
        val ratio=((frequency-frequencies[lo])/(frequencies[lo+1]-frequencies[lo])).coerceIn(0f,1f)
        val b=band(voice.anchors[lo+1],level,blend,index,fraction)
        return a+(b-a)*ratio
    }
}
