package com.tadpole.instrument

import com.tadpole.instrument.input.*
import com.tadpole.instrument.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class PitchGuideTest {
    @Test fun allGuidePositionsSoundThePrintedPitchInEachMeasuredRange() {
        for(range in PitchRange.entries) {
            val settings=InstrumentSettings().withRange(range)
            val marks=PitchGuide.marks(settings)
            assertTrue(marks.size in 27..32)
            for(mark in marks) {
                val actual=InstrumentTouchController.frequencyAt(mark.position,settings)
                assertTrue(abs(1200*log2(actual/mark.frequency))<.02f)
                assertTrue(mark.position in 0f..1f)
            }
            assertTrue(marks.zipWithNext().all {(a,b)->a.position<b.position && b.midi==a.midi+1})
        }
        assertEquals(440f,PitchGuide.frequency(69),0f)
        assertEquals("A4",PitchGuide.reading(440f).label)
        assertEquals("C4",PitchGuide.reading(261.62555f).label)
        assertEquals(20,PitchGuide.reading((440*2.0.pow(20.0/1200)).toFloat()).cents)
        assertEquals(-20,PitchGuide.reading((440*2.0.pow(-20.0/1200)).toFloat()).cents)
    }
    @Test fun customCalibrationMovesGuidesAndKeepsExactBoundaryNotes() {
        val settings=InstrumentSettings().withCalibration(PitchCalibration(261.62555f,440f,880f))
        val marks=PitchGuide.marks(settings)
        assertEquals("C4",marks.first().label);assertEquals("A5",marks.last().label)
        assertEquals(0f,marks.first().position,.00001f)
        assertEquals(1f,marks.last().position,.00001f)
        assertEquals(.5f,marks.single {it.midi==69}.position,.00001f)
        val before=PitchGuide.marks(InstrumentSettings()).single {it.midi==69}.position
        assertTrue(abs(before-.5f)>.05f)
    }
    @Test fun denseLabelsNeverOverlapAndKeepOctaveLandmarks() {
        for(range in PitchRange.entries) for(length in listOf(160f,320f,500f,900f)) {
            val marks=PitchGuide.marks(InstrumentSettings().withRange(range))
            val ids=PitchGuide.labels(marks,length,24f)
            val labels=marks.filter {it.midi in ids}
            // Extremely short stems may fit only octave landmarks plus one note.
            assertTrue("$range at $length px",labels.size>=3)
            assertTrue(labels.all {it.natural})
            assertTrue(marks.filter {it.octaveStart}.all {it.midi in ids})
            assertTrue(labels.zipWithNext().all {(a,b)->(b.position-a.position)*length>=23.999f})
        }
    }
}
