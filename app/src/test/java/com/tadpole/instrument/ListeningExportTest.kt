package com.tadpole.instrument

import com.tadpole.instrument.audio.*
import com.tadpole.instrument.input.InstrumentTouchController
import org.junit.Test
import java.io.File
import java.io.DataOutputStream
import kotlin.math.min

class ListeningExportTest {
    private fun DataOutputStream.le16(v: Int) {writeByte(v and 255);writeByte(v ushr 8 and 255)}
    private fun DataOutputStream.le32(v: Int) {le16(v and 65535);le16(v ushr 16)}
    private fun export(name: String,seconds: Int,control: (Float,AudioParameters) -> Unit) {
        val sr=48000;val frames=sr*seconds
        val file=File("build/previews/$name.wav").apply {parentFile?.mkdirs()}
        DataOutputStream(file.outputStream().buffered()).use {out ->
            out.writeBytes("RIFF");out.le32(36+frames*2);out.writeBytes("WAVEfmt ")
            out.le32(16);out.le16(1);out.le16(1);out.le32(sr);out.le32(sr*2);out.le16(2);out.le16(16)
            out.writeBytes("data");out.le32(frames*2)
            val p=AudioParameters().apply {enabled=true};val synth=SynthEngine(sr);val block=FloatArray(192)
            var done=0
            while(done<frames) {
                control(done.toFloat()/sr,p);val n=min(block.size,frames-done)
                synth.render(block,n,p)
                for(i in 0 until n) out.le16((block[i]*32767).toInt())
                done+=n
            }
        }
    }
    @Test fun exportActualFixedVoiceForListening() {
        export("original_tone_glide",12) {t,p ->
            p.gate=t>.15f && t<11.6f
            p.targetFrequency=when {t<2->213f;t<4->377f;t<6->807f;else->InstrumentTouchController.frequencyAt(((t-6)/5).coerceIn(0f,1f),p.settings)}
        }
        export("rapid_taps",5) {t,p ->p.gate=t>.1f && t<4.8f && (t*8).toInt()%2==0;p.targetFrequency=440f}
        export("head_volume",8) {t,p ->
            p.gate=t>.1f && t<7.7f;p.targetFrequency=377f
            p.targetMouthOpen=when {t<2->0f;t<3->t-2;t<5->1f;t<6->6-t;else->0f}
        }
    }
}
