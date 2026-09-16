@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.tadpole.instrument.ui

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.tadpole.instrument.audio.*
import com.tadpole.instrument.input.*
import com.tadpole.instrument.model.*
import java.util.Locale
import kotlin.math.*

private data class PlayFrame(val frequency: Float,val normalized: Float,val mouthOpen: Float,val gate: Boolean,
    val status: String,val running: Boolean,val error: Boolean,val refresh: Long)

@Composable
fun InstrumentScreen(parameters: AudioParameters,diagnostics: AudioDiagnostics,
    controller: InstrumentTouchController,repository: SettingsRepository,requestPlaying: () -> Boolean) {
    var settings by remember {mutableStateOf(parameters.settings)}
    var showSettings by rememberSaveable {mutableStateOf(false)}
    var debug by rememberSaveable {mutableStateOf(false)}
    var frame by remember {mutableStateOf(PlayFrame(parameters.targetFrequency,.5f,0f,false,"正在准备音色",false,false,0))}
    val view=LocalView.current
    fun apply(value: InstrumentSettings) {
        settings=value.sanitized()
        controller.applySettings(settings)
        repository.save(settings)
    }
    LaunchedEffect(Unit) {
        while(true) {
            withFrameNanos { nanos ->
                frame=PlayFrame(if(parameters.gate) diagnostics.frequency else parameters.targetFrequency,
                    parameters.touchNormalized,if(diagnostics.running) diagnostics.mouthOpen else parameters.targetMouthOpen,parameters.gate,
                    diagnostics.status,diagnostics.running,diagnostics.error,if(debug) nanos/100_000_000L else 0L)
            }
        }
    }
    DisposableEffect(Unit) {onDispose {controller.cancel()}}
    TadpoleTheme {
        Surface(Modifier.fillMaxSize(),color=Paper) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=24.dp)) {
                Row(Modifier.fillMaxWidth().padding(top=18.dp),verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.weight(1f).height(48.dp).background(Color(0xFFEAE2D6),RoundedCornerShape(16.dp)).padding(4.dp),
                        horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        PitchRange.entries.forEach { range ->
                            val selected=settings.range==range
                            Box(Modifier.weight(1f).fillMaxHeight().background(if(selected) Ink else Color.Transparent,RoundedCornerShape(12.dp))
                                .clickable {apply(settings.withRange(range));if(settings.haptic) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)}
                                .testTag("range${range.name}"),contentAlignment=Alignment.Center) {
                                Text(range.name,fontSize=12.sp,fontWeight=FontWeight.SemiBold,letterSpacing=1.sp,
                                    color=if(selected) Paper else Muted)
                            }
                        }
                    }
                    Box(Modifier.size(48.dp).clip(CircleShape).border(1.dp,Line,CircleShape)
                        .combinedClickable(role=Role.Button,onClick={controller.cancel();showSettings=true},
                            onLongClickLabel="切换音频调试信息",onLongClick={controller.cancel();debug=!debug})
                        .semantics {contentDescription="打开设置"}.testTag("settingsButton"),contentAlignment=Alignment.Center) {
                        Canvas(Modifier.size(21.dp)) {
                            for(i in 0..2) {
                                val y=size.height*(.2f+i*.3f)
                                drawLine(Ink,Offset(0f,y),Offset(size.width,y),1.4.dp.toPx(),StrokeCap.Round)
                                drawCircle(Paper,3.dp.toPx(),Offset(size.width*(if(i==1) .3f else .7f),y))
                                drawCircle(Ink,3.dp.toPx(),Offset(size.width*(if(i==1) .3f else .7f),y),style=Stroke(1.4.dp.toPx()))
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top=18.dp),horizontalArrangement=Arrangement.SpaceBetween,
                    verticalAlignment=Alignment.CenterVertically) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).background(if(frame.gate) Accent else Color(0xFF899780),CircleShape))
                        Spacer(Modifier.width(7.dp))
                        val pitch=PitchGuide.reading(frame.frequency)
                        val tuning=when {abs(pitch.cents)<=5->"准";pitch.cents>0->"高 ${pitch.cents} 音分";else->"低 ${-pitch.cents} 音分"}
                        Text(if(frame.gate) "${pitch.label} · $tuning" else "触摸琴杆，开始演奏",
                            fontSize=11.sp,color=if(frame.gate && abs(pitch.cents)<=5) Accent else Muted,
                            modifier=Modifier.testTag("pitchReading"))
                    }
                    Text(String.format(Locale.US,"%.1f Hz",frame.frequency),fontFamily=FontFamily.Monospace,
                        fontSize=12.sp,color=Ink,modifier=Modifier.testTag("frequencyReadout"))
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    InstrumentSurface(frame,settings,controller,requestPlaying)
                    if(debug) DebugPanel(diagnostics,parameters,frame.refresh,Modifier.align(Alignment.TopStart).padding(top=6.dp))
                }
                Text("下滑升音  ·  按住头部放大音量，松手回落",
                    modifier=Modifier.align(Alignment.CenterHorizontally).padding(bottom=16.dp),fontSize=11.sp,color=Muted)
                if(!frame.running || frame.error) {
                    Text(frame.status,fontSize=11.sp,color=Accent,modifier=Modifier.fillMaxWidth()
                        .clickable {requestPlaying()}.padding(bottom=8.dp).testTag("audioStatus"))
                }
            }
            if(showSettings) SettingsScreen(settings,onChange=::apply,onDismiss={showSettings=false})
        }
    }
}

@Composable private fun InstrumentSurface(frame: PlayFrame,settings: InstrumentSettings,
    controller: InstrumentTouchController,requestPlaying: () -> Boolean) {
    val density=LocalDensity.current.density
    val labelSize=with(LocalDensity.current) {12.sp.toPx()}
    val labelGap=labelSize*1.25f+6*density
    val view=LocalView.current
    val paint=remember {android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)}}
    var geometry by remember {mutableStateOf(controller.geometry)}
    val marks=remember(settings.range,settings.calibration) {PitchGuide.marks(settings)}
    val labels=remember(marks,geometry,labelGap) {PitchGuide.labels(marks,geometry.pitchBottom-geometry.pitchTop,labelGap)}
    val guideDescription=remember(settings.range,marks,labels) {
        "${settings.range.name} 音高标记："+marks.filter {it.midi in labels}.joinToString("、") {it.label}
    }
    Canvas(Modifier.fillMaxSize().testTag("instrumentSurface")
        .semantics {contentDescription="连续音高触摸条。上端低音，下端高音。按住琴杆发声，向下滑动升高音高。按住头部张嘴增大音量，松手闭合。$guideDescription"}
        .onSizeChanged {
            controller.cancel()
            geometry=InstrumentGeometry(it.width.toFloat(),it.height.toFloat(),density)
            controller.geometry=geometry
        }
        .pointerInteropFilter {event ->
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN,MotionEvent.ACTION_POINTER_DOWN -> {
                    val index=event.actionIndex
                    val wantsNote=controller.pitchPointerId==-1 && controller.geometry.inPitch(event.getX(index),event.getY(index))
                    if(!wantsNote || requestPlaying()) {
                        val began=controller.down(event.getPointerId(index),event.getX(index),event.getY(index))
                        if(began && settings.haptic) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    var i=0
                    while(i<event.pointerCount) {controller.move(event.getPointerId(i),event.getX(i),event.getY(i));i++}
                }
                MotionEvent.ACTION_UP,MotionEvent.ACTION_POINTER_UP -> controller.up(event.getPointerId(event.actionIndex))
                MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_OUTSIDE -> controller.cancel()
            }
            true
        }) {
        if(size.width<=0f || size.height<=0f) return@Canvas
        val g=controller.geometry
        val u=g.unit
        val cx=g.centerX
        val r=g.headRadius
        val head=g.headY
        val stripWidth=46*u
        val top=g.pitchTop
        val bottom=g.pitchBottom
        // Original curved neck and a wide invisible touch lane.
        val neck=Path().apply {
            moveTo(cx,head-r*.6f);lineTo(cx,top-13*u)
            cubicTo(cx,top-53*u,cx-35*u,top-47*u,cx-50*u,top-34*u)
            cubicTo(cx-62*u,top-25*u,cx-74*u,top-29*u,cx-76*u,top-37*u)
        }
        drawPath(neck,Cocoa,style=Stroke(27*u,cap=StrokeCap.Round))
        drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF936750),Cocoa,Color(0xFF5D3A2B))),
            Offset(cx-stripWidth*.5f,top-8*u),Size(stripWidth,bottom-top+16*u),CornerRadius(22*u))
        // These exact top/bottom coordinates are also the touch mapping endpoints.
        drawRoundRect(Ink,Offset(cx-g.blackHalfWidth,top),Size(g.blackHalfWidth*2,bottom-top),CornerRadius(g.blackHalfWidth))
        drawLine(Color(0xFF645E51),Offset(cx-8*u,top+12*u),Offset(cx-8*u,bottom-12*u),u)
        // Read from the right of the stem so a left-hand finger does not cover names.
        paint.textSize=labelSize;paint.textAlign=android.graphics.Paint.Align.LEFT
        val baseline=-(paint.ascent()+paint.descent())*.5f
        val current=PitchGuide.reading(frame.frequency)
        for(mark in marks) {
            val y=top+(bottom-top)*mark.position
            val near=frame.gate && current.midi==mark.midi && abs(current.cents)<=12
            val labelled=mark.midi in labels
            val color=if(near) Accent else if(labelled) Muted else Line
            if(mark.natural) {
                drawLine(color,Offset(cx+26*u,y),Offset(cx+(if(labelled) 46 else 35)*u,y),
                    (if(mark.octaveStart || near) 1.5f else 1f)*u,StrokeCap.Round)
            } else {
                drawLine(color,Offset(cx-34*u,y),Offset(cx-26*u,y),u,StrokeCap.Round)
            }
            if(labelled) {
                paint.color=color.toArgb();paint.isFakeBoldText=mark.octaveStart || near
                drawContext.canvas.nativeCanvas.drawText(mark.label,cx+54*u,y+baseline,paint)
            }
        }
        if(frame.gate) {
            val y=top+(bottom-top)*frame.normalized
            drawCircle(Accent.copy(alpha=.12f),26*u,Offset(cx,y))
            drawRoundRect(Color(0xFFF2B48A),Offset(cx-17*u,y-3*u),Size(34*u,6*u),CornerRadius(3*u))
            drawCircle(Paper,3*u,Offset(cx,y))
        }
        drawOval(Color(0xFFD9CABA).copy(alpha=.5f),Offset(cx-r*.76f,head+r*.92f),Size(r*1.52f,r*.17f))
        drawCircle(Brush.radialGradient(listOf(Color(0xFF9D735A),Cocoa,Color(0xFF593A2D)),
            center=Offset(cx-r*.38f,head-r*.55f),radius=r*1.9f),r,Offset(cx,head))
        drawArc(Color(0xFFC7A48B).copy(alpha=.4f),208f,62f,false,
            Offset(cx-r*.89f,head-r*.89f),Size(r*1.78f,r*1.78f),style=Stroke(1.2f*u))
        val eyeY=head-r*.3f
        drawCircle(Paper,r*.065f,Offset(cx-r*.36f,eyeY))
        drawCircle(Paper,r*.065f,Offset(cx+r*.36f,eyeY))
        val mouthY=head+r*.17f
        val opening=frame.mouthOpen.coerceIn(0f,1f)
        val spread=r*.82f
        val lip=Path().apply {
            moveTo(cx-spread,mouthY)
            cubicTo(cx-r*.4f,mouthY+r*.035f,cx+r*.4f,mouthY+r*.035f,cx+spread,mouthY)
            cubicTo(cx+r*.53f,mouthY+r*(.035f+.55f*opening),cx-r*.53f,mouthY+r*(.035f+.55f*opening),cx-spread,mouthY)
            close()
        }
        drawPath(lip,Color(0xFF2A1D18))
        drawPath(lip,Color(0xFF452B21),style=Stroke(1.2f*u))
        if(opening>.1f) drawOval(Color(0xFFA96952),Offset(cx-r*.23f,mouthY+r*opening*.33f),
            Size(r*.46f,r*opening*.1f),alpha=opening*.65f)
    }
}

@Composable private fun DebugPanel(d: AudioDiagnostics,p: AudioParameters,refresh: Long,modifier: Modifier) {
    // UI-only formatting. refresh drives the panel even when the pitch is held steady.
    @Suppress("UNUSED_VARIABLE") val sampledFrame=refresh
    Surface(modifier.widthIn(max=240.dp).testTag("debugPanel"),shape=RoundedCornerShape(12.dp),color=Ink.copy(alpha=.94f)) {
        Text(buildString {
            append("ENGINE / DEBUG\n")
            append(String.format(Locale.US,"Pitch: %.2f Hz\nPosition: %.3f (top=0)\n",d.frequency,p.touchNormalized))
            append(String.format(Locale.US,"Mouth: %.2f (gain only)\nBass: %+.1f  Treble: %+.1f dB\n",d.mouthOpen,p.settings.bassDb,p.settings.trebleDb))
            append("Rate: ${d.sampleRate} Hz · PCM16\nBlock: ${d.burstFrames} frames\nNative hint: ${d.nativeBurstFrames}\nBuffer: ${d.bufferFrames} / ${d.capacityFrames}\n")
            append("Underruns: ${d.underruns}\nLow latency: ${d.lowLatency}\n")
            append(if(d.queuedMilliseconds>=0) String.format(Locale.US,"Queue estimate: %.1f ms\n",d.queuedMilliseconds) else "Queue estimate: unavailable\n")
            append(String.format(Locale.US,"Render: %.1f%%  Peak: %.3f\n",d.renderLoad*100,d.peak))
            append("REFERENCE / FIXED\n不含触摸/扬声器总延迟")
        },modifier=Modifier.padding(12.dp),color=Paper,fontFamily=FontFamily.Monospace,fontSize=10.sp,lineHeight=16.sp)
    }
}
