package com.tadpole.instrument.input

import com.tadpole.instrument.audio.AudioParameters
import com.tadpole.instrument.model.InstrumentSettings
import com.tadpole.instrument.model.MeasuredRanges
import kotlin.math.ln

/** Geometry shared by drawing and hit testing; all coordinates are local pixels. */
data class InstrumentGeometry(val width: Float,val height: Float,val density: Float) {
    // Scale the drawing as a whole on short/narrow screens instead of pushing its
    // lower touch endpoint underneath the head. One geometry drives draw + input.
    val unit=minOf(density,height/360f,width/280f).coerceAtLeast(.001f)
    val centerX=width*.5f
    val headRadius=minOf(width*.28f,height*.20f)
    val headY=height-headRadius-14*unit
    val headTop=headY-headRadius
    val pitchTop=70*unit
    val pitchBottom=headTop-14*unit
    val pitchHalfWidth=minOf(width*.44f,80*unit)
    val blackHalfWidth=12*unit
    fun inPitch(x: Float,y: Float) = y in pitchTop..pitchBottom && x in centerX-pitchHalfWidth..centerX+pitchHalfWidth
    fun inHead(x: Float,y: Float): Boolean {
        val dx=x-centerX;val dy=y-headY
        return dx*dx+dy*dy<=headRadius*headRadius
    }
}

/** Pointer IDs, never array indices. A third finger cannot steal either active role. */
class InstrumentTouchController(private val parameters: AudioParameters) {
    var geometry=InstrumentGeometry(360f,560f,1f)
    var pitchPointerId=-1; private set
    var mouthPointerId=-1; private set
    private var mouthDownY=0f
    val playing get()=pitchPointerId!=-1

    fun applySettings(value: InstrumentSettings) {
        parameters.settings=value.sanitized()
        parameters.targetFrequency=frequencyAt(parameters.touchNormalized,parameters.settings)
    }
    fun down(id: Int,x: Float,y: Float): Boolean {
        if(id==pitchPointerId || id==mouthPointerId) return false
        if(pitchPointerId==-1 && geometry.inPitch(x,y)) {
            pitchPointerId=id
            updatePitch(x,y)
            // Publish position BEFORE gate so a newly acquired note has the correct pitch.
            parameters.gate=true
            return true
        }
        if(mouthPointerId==-1 && geometry.inHead(x,y)) {
            mouthPointerId=id
            mouthDownY=y
            parameters.targetMouthOpen=1f
        }
        return false
    }
    fun move(id: Int,x: Float,y: Float) {
        if(id==pitchPointerId && pitchPointerId!=-1) updatePitch(x,y)
        if(id==mouthPointerId && mouthPointerId!=-1 && y.isFinite()) {
            parameters.targetMouthOpen=(1-(y-mouthDownY)/(geometry.headRadius*.8f)).coerceIn(0f,1f)
        }
    }
    fun up(id: Int) {
        if(id==pitchPointerId) {pitchPointerId=-1;parameters.gate=false}
        if(id==mouthPointerId) {mouthPointerId=-1;parameters.targetMouthOpen=0f}
    }
    fun cancel() {pitchPointerId=-1;mouthPointerId=-1;parameters.gate=false;parameters.targetMouthOpen=0f}
    private fun updatePitch(x: Float,y: Float) {
        if(!x.isFinite() || !y.isFinite()) return
        val g=geometry
        val normalized=((y-g.pitchTop)/(g.pitchBottom-g.pitchTop)).coerceIn(0f,1f)
        parameters.touchNormalized=normalized
        parameters.targetFrequency=frequencyAt(normalized,parameters.settings)
    }
    companion object {
        /** Monotone cubic interpolation of measured top/middle/bottom log frequencies.
         * Calibration changes note spacing continuously; it never creates frets or plateaus. */
        fun frequencyAt(normalized: Float,s: InstrumentSettings): Float {
            val c = s.calibration
            if(c.topHz==s.range.topHz && c.middleHz==s.range.middleHz && c.bottomHz==s.range.bottomHz)
                return MeasuredRanges.frequencyAt(s.range,normalized)
            val p = if (normalized.isFinite()) normalized.coerceIn(0f,1f).toDouble() else .5
            val a = ln(c.topHz.toDouble())
            val b = ln(c.middleHz.toDouble())
            val z = ln(c.bottomHz.toDouble())
            val d0 = 2 * (b-a)
            val d1 = 2 * (z-b)
            val m0 = ((3*d0-d1)/2).coerceIn(0.0,3*d0)
            val m1 = 2*d0*d1/(d0+d1)
            val m2 = ((3*d1-d0)/2).coerceIn(0.0,3*d1)
            val u = if(p<=.5) p*2 else (p-.5)*2
            val v0 = if(p<=.5) a else b
            val v1 = if(p<=.5) b else z
            val t0 = if(p<=.5) m0 else m1
            val t1 = if(p<=.5) m1 else m2
            val y = (2*u*u*u-3*u*u+1)*v0 + (u*u*u-2*u*u+u)*.5*t0 +
                (-2*u*u*u+3*u*u)*v1 + (u*u*u-u*u)*.5*t1
            return kotlin.math.exp(y).toFloat()
        }
    }
}
