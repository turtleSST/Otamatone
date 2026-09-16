package com.tadpole.instrument.input

import com.tadpole.instrument.model.InstrumentSettings
import kotlin.math.*

data class PitchMark(val midi: Int,val frequency: Float,val position: Float,val label: String) {
    val natural: Boolean get()=when(midi%12) {0,2,4,5,7,9,11->true;else->false}
    val octaveStart: Boolean get()=midi%12==0
}

data class PitchReading(val midi: Int,val label: String,val cents: Int)

/** Display guidance only. Uses the same continuous mapping as the sounding finger. */
object PitchGuide {
    private val names=arrayOf("C","C♯","D","D♯","E","F","F♯","G","G♯","A","A♯","B")
    private fun midiAt(frequency: Float)=69+12*log2(frequency.toDouble()/440)
    private fun name(midi: Int)=names[midi%12]+(midi/12-1)
    fun frequency(midi: Int)=(440*2.0.pow((midi-69)/12.0)).toFloat()
    fun reading(frequency: Float): PitchReading {
        val actual=midiAt(if(frequency.isFinite() && frequency>0f) frequency else 440f)
        val midi=actual.roundToInt().coerceIn(0,127)
        return PitchReading(midi,name(midi),((actual-midi)*100).roundToInt())
    }

    fun marks(settings: InstrumentSettings): List<PitchMark> {
        val calibration=settings.calibration
        val first=ceil(midiAt(calibration.topHz)-1e-5).toInt().coerceIn(0,127)
        val last=floor(midiAt(calibration.bottomHz)+1e-5).toInt().coerceIn(0,127)
        return (first..last).map { midi ->
            val hz=frequency(midi)
            var low=0f;var high=1f
            // Invert the measured / custom monotone curve; no equal-spacing shortcut.
            repeat(24) {
                val middle=(low+high)*.5f
                if(InstrumentTouchController.frequencyAt(middle,settings)<hz) low=middle else high=middle
            }
            PitchMark(midi,hz,(low+high)*.5f,name(midi))
        }
    }

    /** Keep octave landmarks, then A, then other natural notes that fit. Tick
     * positions never move, and every semitone remains available as a fine tick. */
    fun labels(marks: List<PitchMark>,length: Float,minimumGap: Float): Set<Int> {
        if(length<=0f || minimumGap<=0f) return emptySet()
        val selected=ArrayList<PitchMark>()
        val priority=marks.filter {it.natural}.sortedWith(compareBy<PitchMark> {
            when(it.midi%12) {0->0;9->1;4,7->2;else->3}
        }.thenBy {it.midi})
        for(mark in priority) if(selected.all {abs(it.position-mark.position)*length>=minimumGap}) selected.add(mark)
        return selected.mapTo(HashSet()) {it.midi}
    }
}
