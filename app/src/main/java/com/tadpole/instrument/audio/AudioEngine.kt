package com.tadpole.instrument.audio

import android.content.Context
import android.media.*
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/** Main thread owns focus/session requests; one serial audio executor owns all tracks. */
class AudioEngine(context: Context,val parameters: AudioParameters,val diagnostics: AudioDiagnostics) {
    private val manager=context.getSystemService(AudioManager::class.java)
    private val main=Handler(Looper.getMainLooper())
    private val executor=Executors.newSingleThreadExecutor { task -> Thread(task,"Tadpole-Audio") }
    private var foreground=false
    private var closed=false
    private var focused=false
    private var session: Session?=null
    private var bank: WavetableBank?=null // Accessed only by audio executor.
    private var warmed=false
    var onCancelGesture: (() -> Unit)?=null
    private class Session { @Volatile var stop=false }
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val focusRequest=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener({ change ->
            when(change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    focused=true
                    if(foreground && !closed) startSession()
                }
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    focused=false
                    onCancelGesture?.invoke()
                    stopSession()
                    diagnostics.status="音频被其他应用占用 · 点按重试"
                }
            }
        },main).build()

    fun resume() { if(closed)return;foreground=true;requestPlaying() }
    /** Called only on a new gesture or explicit retry, never for ACTION_MOVE. */
    fun requestPlaying(): Boolean {
        if(!foreground || closed) return false
        if(!focused) focused=manager.requestAudioFocus(focusRequest)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if(focused) startSession() else diagnostics.status="无法取得音频焦点 · 点按重试"
        return focused
    }
    private fun startSession() {
        if(session?.stop==false) return
        val next=Session()
        session=next
        parameters.enabled=true
        diagnostics.error=false
        executor.execute { runSession(next) }
    }
    private fun stopSession() {
        parameters.gate=false
        parameters.enabled=false
        session?.stop=true
    }
    fun pause() {
        foreground=false
        onCancelGesture?.invoke()
        stopSession()
        if(focused) manager.abandonAudioFocusRequest(focusRequest)
        // Also abandon transient focus requests, so a late gain cannot restart in background.
        if(!focused) manager.abandonAudioFocusRequest(focusRequest)
        focused=false
    }
    fun close() {if(closed)return;pause();closed=true;executor.shutdown();onCancelGesture=null}

    private fun runSession(run: Session) {
        var track: AudioTrack?=null
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
            if(run.stop) return
            val tables=bank ?: WavetableBank().also {bank=it}
            if(run.stop) return
            val reportedRate=manager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
            val nativeRate=AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
            val rate=(reportedRate ?: nativeRate).takeIf {it in 8000..192000} ?: 48000
            val nativeBurst=(manager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()
                ?: 192).coerceIn(64,max(64,rate/20))
            val burst=min(nativeBurst,max(64,rate/100))
            // A large HAL quantum must fit even when render blocks are smaller.
            // The emulator's 1088-frame HAL cannot be fed by a 960-frame queue.
            val initialFrames=max(burst*2,nativeBurst+burst)
            val minBytes=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT)
            check(minBytes>0) {"Unsupported PCM output: $rate Hz"}
            // Reserve up to 6 bursts, START at 2; capacity does not add queued latency.
            val capacity=max(minBytes/2,max(burst*6,initialFrames))
            if(!warmed) {
                // Exercise DSP/JIT before starting the hardware deadline. No audible output.
                // Use a separate synth so a finger held during startup still gets a fresh attack.
                val warmParameters=AudioParameters().apply {enabled=true;gate=true;settings=parameters.settings}
                val warmSynth=SynthEngine(rate,tables)
                val scratch=FloatArray(burst)
                var warmBlock=0
                while(warmBlock<96) {
                    if(run.stop) return
                    warmParameters.settings=parameters.settings.withRange(com.tadpole.instrument.model.PitchRange.entries[(warmBlock/16)%3])
                    warmParameters.targetFrequency=110f+warmBlock*7f
                    warmSynth.render(scratch,burst,warmParameters)
                    warmBlock++
                }
                warmed=true
            }
            val format=AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            val created=AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(capacity*2)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build()
            track=created
            check(created.state==AudioTrack.STATE_INITIALIZED) {"AudioTrack initialization failed"}
            created.setBufferSizeInFrames(initialFrames)
            val synth=SynthEngine(rate,tables)
            val floating=FloatArray(burst)
            val pcm=ShortArray(burst)
            val stamp=AudioTimestamp()
            diagnostics.sampleRate=rate
            diagnostics.burstFrames=burst
            diagnostics.nativeBurstFrames=nativeBurst
            diagnostics.bufferFrames=created.bufferSizeInFrames
            diagnostics.capacityFrames=created.bufferCapacityInFrames
            diagnostics.lowLatency=created.performanceMode==AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
            diagnostics.underruns=0
            diagnostics.queuedMilliseconds=-1f
            if(run.stop) return
            // Prime the initial effective queue while stopped; no latency beyond the set buffer.
            var written=0L
            val primeFrames=min(created.bufferSizeInFrames,initialFrames)
            while(written<primeFrames) {
                val n=min(pcm.size,primeFrames-written.toInt())
                val result=created.write(pcm,0,n,AudioTrack.WRITE_NON_BLOCKING)
                if(result<=0) break
                written+=result
            }
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            created.play()
            diagnostics.running=true
            diagnostics.status="可以演奏"
            var framesSinceStats=0
            var previousUnderruns=0
            var lastGrow=System.nanoTime()
            var renderNanos=0L
            var renderFrames=0
            while(!run.stop || !synth.isSilent) {
                val renderStart=System.nanoTime()
                synth.render(floating,burst,parameters,run.stop)
                var n=0
                while(n<burst) {pcm[n]=(floating[n]*32767f).toInt().toShort();n++}
                renderNanos+=System.nanoTime()-renderStart
                renderFrames+=burst
                var offset=0
                while(offset<burst) {
                    val result=created.write(pcm,offset,burst-offset,AudioTrack.WRITE_BLOCKING)
                    check(result>0) {"AudioTrack write error $result"}
                    offset+=result;written+=result
                }
                diagnostics.frequency=synth.currentFrequency
                diagnostics.mouthOpen=synth.currentMouthOpen
                diagnostics.peak=synth.peak
                framesSinceStats+=burst
                if(framesSinceStats>=rate/4) {
                    framesSinceStats=0
                    val underruns=created.underrunCount
                    val now=System.nanoTime()
                    // Bounded adaptive safety: at most ~30 ms or six bursts, never 500 ms.
                    val maximum=min(created.bufferCapacityInFrames,max(initialFrames,min(burst*6,rate*30/1000)))
                    if(underruns>previousUnderruns && now-lastGrow>1_000_000_000L && created.bufferSizeInFrames<maximum) {
                        created.setBufferSizeInFrames(min(maximum,created.bufferSizeInFrames+burst))
                        lastGrow=now
                    }
                    previousUnderruns=underruns
                    diagnostics.underruns=underruns
                    diagnostics.bufferFrames=created.bufferSizeInFrames
                    diagnostics.renderLoad=(renderNanos/1e9*rate/renderFrames).toFloat()
                    renderNanos=0;renderFrames=0
                    diagnostics.queuedMilliseconds=if(created.getTimestamp(stamp)) {
                        val played=stamp.framePosition+(now-stamp.nanoTime)*rate/1e9
                        ((written-played).coerceAtLeast(0.0)*1000/rate).toFloat()
                    } else -1f
                }
            }
            // Let the bounded queue carry the release tail before discarding silent frames.
            if(created.playState==AudioTrack.PLAYSTATE_PLAYING) {
                Thread.sleep((created.bufferSizeInFrames*1000L/rate+2).coerceAtMost(35))
                created.pause()
                created.flush()
            }
        } catch(e: Exception) {
            if(!run.stop) {
                diagnostics.error=true
                diagnostics.status="音频输出异常 · 点按重试 (${e.javaClass.simpleName})"
                parameters.gate=false
                run.stop=true
                main.post {if(session===run) onCancelGesture?.invoke()}
            }
        } finally {
            track?.release()
            diagnostics.running=false
            diagnostics.peak=0f
            diagnostics.mouthOpen=0f
            if(!diagnostics.error && run.stop) diagnostics.status="已暂停"
        }
    }
}
