package com.tadpole.instrument

import com.tadpole.instrument.audio.*
import com.tadpole.instrument.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class ReferenceSynthTest {
    companion object { private val bank=WavetableBank() }
    private fun parameters(f: Float=440f)=AudioParameters().apply {
        targetFrequency=f;gate=true;enabled=true;targetMouthOpen=1f;settings=settings.withTone(0f,0f)
    }
    private fun render(s: SynthEngine,p: AudioParameters,seconds: Float): FloatArray {
        val output=FloatArray((s.sampleRate*seconds).toInt());val block=FloatArray(192)
        var offset=0
        while(offset<output.size) {
            val n=min(block.size,output.size-offset)
            s.render(block,n,p);System.arraycopy(block,0,output,offset,n);offset+=n
        }
        return output
    }
    private fun amplitude(x: FloatArray,f: Double,rate: Int=48000,hann: Boolean=false): Double {
        var re=0.0;var im=0.0;var sum=0.0
        for(i in x.indices) {
            val w=if(hann) .5-.5*cos(2*PI*i/(x.size-1)) else 1.0
            val angle=2*PI*f*i/rate
            re+=w*x[i]*cos(angle);im+=w*x[i]*sin(angle);sum+=w
        }
        return 2*hypot(re,im)/sum
    }
    @Test fun tenSecondHoldHasNoAutomaticVibratoOrPitchDrift() {
        val p=parameters();val s=SynthEngine(48000,bank)
        val out=render(s,p,10f);val tail=out.copyOfRange(out.size-48000,out.size)
        assertEquals(440f,s.currentFrequency,.001f)
        val a=amplitude(tail,880.0)
        assertTrue(a>.01)
        assertTrue(a>amplitude(tail,879.0)*100)
        assertTrue(a>amplitude(tail,881.0)*100)
        assertTrue(out.all {it.isFinite() && abs(it)<=.941f})
    }
    @Test fun fastPitchChangesRemainContinuous() {
        val p=parameters(213f);val s=SynthEngine(48000,bank);val b=FloatArray(48)
        render(s,p,.05f);p.targetFrequency=1000f
        var previous=s.currentFrequency;var intermediate=0
        repeat(40) {
            s.render(b,b.size,p)
            assertTrue(s.currentFrequency>=previous)
            if(s.currentFrequency in 214f..999f) intermediate++
            previous=s.currentFrequency
        }
        assertTrue(intermediate>20)
        assertEquals(1000f,s.currentFrequency,.1f)
    }
    @Test fun envelopeRetriggersSmoothlyAndEndsAtExactZero() {
        val env=Envelope(48000)
        assertTrue(env.next(true)<.0001f)
        repeat(600) {env.next(true)}
        repeat(100) {env.next(false)}
        val level=env.value
        assertTrue(abs(env.next(true)-level)<.0001f)
        repeat(1500) {env.next(false)}
        assertTrue(env.silent)
        assertEquals(0f,env.value,0f)
    }
    @Test fun rapidTapsAreBoundedAndReleaseIsSilent() {
        val p=parameters();val s=SynthEngine(48000,bank);val b=FloatArray(192)
        repeat(120) {
            p.gate=it%4<2;s.render(b,b.size,p)
            assertTrue(b.all {v->v.isFinite() && abs(v)<=.941f})
        }
        p.gate=false
        val tail=render(s,p,.08f)
        assertTrue(tail.takeLast(2000).all {it==0f})
        assertTrue(s.isSilent)
    }
    @Test fun nativeRatesAndWholePitchRangeStayFinite() {
        for(rate in intArrayOf(44100,48000,96000)) {
            val s=SynthEngine(rate,bank);val p=parameters();val b=FloatArray(128)
            p.settings=p.settings.copy(masterVolume=1f)
            for(range in PitchRange.entries) for(f in floatArrayOf(20f,54.5f,203.8f,440f,806f,1058f,2000f,4444f,8000f)) {
                p.settings=p.settings.withRange(range)
                p.targetFrequency=f
                repeat(100) {s.render(b,b.size,p);assertTrue(b.all {v->v.isFinite() && abs(v)<=.941f})}
            }
        }
    }
    @Test fun disabledTransportCannotLeaveAnOldNotePlaying() {
        val s=SynthEngine(48000,bank);val p=parameters();render(s,p,.1f)
        p.enabled=false
        assertTrue(render(s,p,.08f).takeLast(2000).all {it==0f})
        p.enabled=true;p.gate=false
        assertTrue(render(s,p,.02f).all {it==0f})
    }
    @Test fun bandLimitedReferenceHasLittleFoldedHarmonicEnergy() {
        val o=Oscillator(bank,48000)
        val data=FloatArray(48000) {o.next(1760f)}
        var wanted=0.0;var aliases=0.0
        for(h in 1..12) wanted+=amplitude(data,h*1760.0).pow(2)
        for(h in 14..26) {
            val folded=abs(((h*1760.0+24000)%48000)-24000)
            if(folded>100 && abs(folded/1760-round(folded/1760))>.01) aliases+=amplitude(data,folded).pow(2)
        }
        assertTrue(10*log10(max(aliases,1e-20)/wanted)<-50)
    }
    @Test fun limiterIsTransparentBelowThreshold() {
        val limiter=Limiter(48000)
        assertEquals(.25f,limiter.process(.25f),.000001f)
        repeat(1000) {assertTrue(abs(limiter.process(sin(it*.1).toFloat()*20f))<=.941f)}
        assertEquals(0f,limiter.process(Float.NaN),0f)
    }
    @Test fun independentHeldOutRecordingHarmonicsRemainFaithful() {
        // Whole third sweep is excluded from each range's timbre/phase/level fit.
        val rows=javaClass.getResourceAsStream("/range_validation.csv")!!.bufferedReader().use {it.readLines()}
        var totalError=0.0
        for(row in rows) {
            val fields=row.split(',');val range=PitchRange.valueOf(fields[0]);val f=fields[1].toDouble()
            val reference=fields.drop(2).map {it.toDouble()};val strongest=reference.max()
            val s=SynthEngine(48000,bank);val p=parameters(f.toFloat())
            p.settings=p.settings.withRange(range);render(s,p,.25f)
            val out=render(s,p,.2f)
            val weights=ArrayList<Double>();val errors=ArrayList<Double>()
            for(h in 1..reference.size) {
                val expected=reference[h-1]
                if(h*f<=16000 && expected>=strongest*10.0.pow(-35.0/20)) {
                    val actual=amplitude(out,h*f,hann=true)
                    weights.add(expected*expected);errors.add(20*log10(max(actual,1e-10)/expected))
                }
            }
            val sum=weights.sum();val gain=errors.indices.sumOf {weights[it]*errors[it]}/sum
            val rmse=sqrt(errors.indices.sumOf {weights[it]*(errors[it]-gain).pow(2)}/sum)
            assertTrue("Held-out $range tone $f Hz: $rmse dB",rmse<4.5)
            totalError+=rmse
        }
        assertEquals(27,rows.size)
        assertTrue("Average held-out spectral error ${totalError/rows.size}",totalError/rows.size<2.2)
    }
    @Test fun highRangeReachesMeasuredEndpointAboveOldCeiling() {
        val p=parameters(PitchRange.HIGH.bottomHz);p.settings=p.settings.withRange(PitchRange.HIGH)
        val s=SynthEngine(48000,bank);render(s,p,.2f)
        assertTrue(s.currentFrequency>4400f)
        assertEquals(PitchRange.HIGH.bottomHz,s.currentFrequency,.01f)
        assertTrue(amplitude(render(s,p,.2f),PitchRange.HIGH.bottomHz.toDouble(),hann=true)>.01)
    }
    @Test fun rangeChangesCrossfadeWithoutAnInstantSampleJump() {
        val a=SynthEngine(48000,bank);val b=SynthEngine(48000,bank)
        val p=parameters(850f);val q=parameters(850f)
        render(a,p,.1f);render(b,q,.1f)
        p.settings=p.settings.withRange(PitchRange.HIGH)
        val x=FloatArray(1);val y=FloatArray(1)
        a.render(x,1,p);b.render(y,1,q)
        assertTrue(abs(x[0]-y[0])<.003f)
        var difference=0.0
        val xx=render(a,p,.05f);val yy=render(b,q,.05f)
        for(i in xx.indices) difference+=(xx[i]-yy[i]).pow(2)
        assertTrue(difference>1e-3)
        repeat(30) {
            p.settings=p.settings.withRange(PitchRange.entries[it%3])
            assertTrue(render(a,p,.003f).all {v->v.isFinite() && abs(v)<=.941f})
        }
    }
    @Test fun headOpeningChangesGainWithoutChangingHarmonicBalance() {
        fun tone(open: Float): FloatArray {
            val p=parameters(377f);p.targetMouthOpen=open
            val s=SynthEngine(48000,bank);render(s,p,.25f)
            return render(s,p,.2f)
        }
        val closed=tone(0f);val open=tone(1f)
        val expected=ReferenceTimbre.openMouthGain/ReferenceTimbre.closedMouthGain
        for(h in 1..16) {
            val a=amplitude(closed,h*377.0,hann=true)
            if(a>.0001) assertEquals(expected.toDouble(),amplitude(open,h*377.0,hann=true)/a,.002)
        }
    }
    @Test fun savedZeroMasterIsSilentOnFirstTouch() {
        val p=parameters();p.settings=p.settings.copy(masterVolume=0f)
        assertTrue(render(SynthEngine(48000,bank),p,.1f).all {it==0f})
    }
    @Test fun compensationRaisesBothEndsAndRemainsBoundedDuringChanges() {
        fun filterGain(f: Double): Double {
            val low=ShelfFilter(48000,600f,false);val high=ShelfFilter(48000,4500f,true)
            low.setGain(3f);high.setGain(2f)
            val x=FloatArray(48000) {high.process(low.process((.1*sin(2*PI*f*it/48000)).toFloat()))}
            return 20*log10(amplitude(x.copyOfRange(24000,48000),f)/.1)
        }
        assertEquals(3.0,filterGain(100.0),.1)
        assertEquals(2.0,filterGain(12000.0),.1)
        val p=parameters();val s=SynthEngine(48000,bank);val b=FloatArray(192)
        repeat(200) {
            p.settings=p.settings.withTone(if(it%2==0) 9f else -6f,if(it%3==0) 9f else -6f)
            p.targetMouthOpen=if(it%4<2) 0f else 1f
            s.render(b,192,p)
            assertTrue(b.all {v->v.isFinite() && abs(v)<=.941f})
        }
    }
}
