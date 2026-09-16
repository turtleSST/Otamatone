package com.tadpole.instrument

import com.tadpole.instrument.audio.*
import com.tadpole.instrument.input.*
import com.tadpole.instrument.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class StemTouchTest {
    private val p=AudioParameters()
    private val c=InstrumentTouchController(p).apply {geometry=InstrumentGeometry(400f,600f,1f)}
    @Test fun physicalDirectionIsLowAtTopAndHighAtBottom() {
        c.down(7,200f,c.geometry.pitchTop)
        assertEquals(PitchRange.MID.topHz,p.targetFrequency,.001f)
        c.move(7,200f,c.geometry.pitchBottom)
        assertEquals(PitchRange.MID.bottomHz,p.targetFrequency,.001f)
        assertEquals(1f,p.touchNormalized,0f)
    }
    @Test fun customThreePointCalibrationIsExactAndMonotone() {
        val s=InstrumentSettings().withCalibration(PitchCalibration(215f,350f,990f))
        assertEquals(215f,InstrumentTouchController.frequencyAt(0f,s),.001f)
        assertEquals(350f,InstrumentTouchController.frequencyAt(.5f,s),.001f)
        assertEquals(990f,InstrumentTouchController.frequencyAt(1f,s),.001f)
        var previous=0f
        for(i in 0..1000) {
            val f=InstrumentTouchController.frequencyAt(i/1000f,s)
            assertTrue(f>previous);previous=f
        }
        val left=InstrumentTouchController.frequencyAt(.5f,s)-InstrumentTouchController.frequencyAt(.499f,s)
        val right=InstrumentTouchController.frequencyAt(.501f,s)-InstrumentTouchController.frequencyAt(.5f,s)
        assertTrue(kotlin.math.abs(left-right)<.02f)
    }
    @Test fun fingersOnHeadAndThirdPointerCannotStealPitch() {
        c.down(7,200f,200f);val f=p.targetFrequency
        c.down(19,200f,c.geometry.headY);c.move(19,240f,c.geometry.headY-50f)
        c.down(3,200f,300f);c.move(3,200f,350f)
        assertEquals(f,p.targetFrequency,0f)
        c.up(19);c.up(3)
        assertTrue(p.gate);assertEquals(7,c.pitchPointerId)
        c.up(7);assertFalse(p.gate)
    }
    @Test fun touchingHeadAloneDoesNotSoundAndDoesNotReserveAController() {
        assertFalse(c.down(2,200f,c.geometry.headY))
        assertFalse(p.gate)
        c.down(4,200f,180f)
        assertTrue(p.gate);assertEquals(4,c.pitchPointerId)
    }
    @Test fun horizontalAndIgnoredThirdFingerMotionProduceBitIdenticalAudio() {
        val other=AudioParameters();val second=InstrumentTouchController(other).apply {geometry=c.geometry}
        c.down(7,200f,200f);second.down(7,200f,200f)
        p.enabled=true;other.enabled=true
        val bank=WavetableBank();val a=SynthEngine(48000,bank);val b=SynthEngine(48000,bank)
        val x=FloatArray(192);val y=FloatArray(192)
        repeat(80) {
            c.move(7,if(it%2==0) 40f else 360f,200f)
            c.down(19,0f,0f);c.move(19,0f,30f);c.up(19)
            a.render(x,192,p);b.render(y,192,other)
            assertArrayEquals(x,y,0f)
        }
    }
    @Test fun capturedFingerClampsOutsideStripAndCancelStops() {
        c.down(2,200f,180f);c.move(2,-100f,-10f)
        assertEquals(PitchRange.MID.topHz,p.targetFrequency,.001f);assertTrue(p.gate)
        c.move(2,800f,900f);assertEquals(PitchRange.MID.bottomHz,p.targetFrequency,.001f)
        c.cancel();assertFalse(p.gate);assertEquals(-1,c.pitchPointerId)
    }
    @Test fun threeRangesRetainIndependentCalibrations() {
        var s=InstrumentSettings().withCalibration(PitchCalibration(220f,400f,1010f))
        s=s.withRange(PitchRange.LOW).withCalibration(PitchCalibration(105f,200f,490f))
        assertEquals(490f,s.calibration.bottomHz,0f)
        assertEquals(1010f,s.withRange(PitchRange.MID).calibration.bottomHz,0f)
        c.down(11,200f,200f);val f=p.targetFrequency
        c.applySettings(InstrumentSettings().withRange(PitchRange.HIGH))
        assertTrue(p.targetFrequency>f*3f);assertEquals(11,c.pitchPointerId)
        assertEquals(InstrumentTouchController.frequencyAt(p.touchNormalized,p.settings),p.targetFrequency,.002f)
    }
    @Test fun measuredCurvesCoverAllRangesAndHaveNoReversedOrSteppedNotes() {
        val ends=arrayOf(floatArrayOf(54.49f,292.18f),floatArrayOf(203.79f,1057.64f),floatArrayOf(806.26f,4443.68f))
        for(range in PitchRange.entries) {
            val s=InstrumentSettings().withRange(range)
            assertEquals(ends[range.ordinal][0],InstrumentTouchController.frequencyAt(0f,s),.03f)
            assertEquals(ends[range.ordinal][1],InstrumentTouchController.frequencyAt(1f,s),.03f)
            assertEquals(range.middleHz,InstrumentTouchController.frequencyAt(.5f,s),.002f)
            var previous=0f
            for(i in 0..4000) {
                val f=InstrumentTouchController.frequencyAt(i/4000f,s)
                assertTrue(f>previous);previous=f
            }
            for(i in 1..15) {
                val x=i/16f
                val l=InstrumentTouchController.frequencyAt(x,s)-InstrumentTouchController.frequencyAt(x-.0001f,s)
                val r=InstrumentTouchController.frequencyAt(x+.0001f,s)-InstrumentTouchController.frequencyAt(x,s)
                assertTrue(abs(l-r)<.01f)
            }
        }
    }
    @Test fun invalidCalibrationAndCoordinatesAreContained() {
        val s=InstrumentSettings(mid=PitchCalibration(Float.NaN,-10f,Float.POSITIVE_INFINITY),masterVolume=-4f).sanitized()
        assertTrue(s.mid.topHz<s.mid.middleHz && s.mid.middleHz<s.mid.bottomHz)
        assertEquals(0f,s.masterVolume,0f)
        c.down(1,200f,200f);val f=p.targetFrequency
        c.move(1,Float.NaN,Float.POSITIVE_INFINITY);assertEquals(f,p.targetFrequency,0f)
    }
    @Test fun blackStripAndBothTouchEndpointsStayAboveTheHeadOnSmallAndLargeScreens() {
        for(density in listOf(1f,2.625f,4f)) for(widthDp in listOf(240f,320f,412f,840f)) for(heightDp in listOf(150f,260f,400f,650f)) {
            val g=InstrumentGeometry(widthDp*density,heightDp*density,density)
            assertTrue(g.pitchTop>0f)
            assertTrue(g.pitchBottom>g.pitchTop+24*g.unit)
            assertTrue(g.pitchBottom+8*g.unit<g.headTop)
            assertTrue(g.headY+g.headRadius<g.height)
            assertTrue(g.inPitch(g.centerX,g.pitchTop))
            assertTrue(g.inPitch(g.centerX,g.pitchBottom))
            assertFalse(g.inPitch(g.centerX,g.pitchBottom+g.unit))
            assertFalse(g.inPitch(g.centerX,g.pitchTop-g.unit))
            assertFalse(g.inHead(g.centerX,g.pitchBottom))
        }
    }
    @Test fun headPressDragAndReleaseControlOnlyVolumeAndSpringClosed() {
        c.down(7,200f,200f);val f=p.targetFrequency
        c.down(19,200f,c.geometry.headY)
        assertEquals(1f,p.targetMouthOpen,0f)
        c.move(19,200f,c.geometry.headY+c.geometry.headRadius*.4f)
        assertEquals(.5f,p.targetMouthOpen,.001f)
        assertEquals(f,p.targetFrequency,0f)
        c.up(19)
        assertEquals(0f,p.targetMouthOpen,0f);assertTrue(p.gate)
        c.down(19,200f,c.geometry.headY);c.cancel()
        assertEquals(0f,p.targetMouthOpen,0f);assertEquals(-1,c.mouthPointerId);assertFalse(p.gate)
    }
}
