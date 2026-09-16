package com.tadpole.instrument

import android.graphics.PointF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.tadpole.instrument.model.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual MotionEvents through Compose -> pointerInteropFilter -> AudioTrack, on Android. */
@RunWith(AndroidJUnit4::class)
class InstrumentDeviceTest {
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var device: UiDevice
    private var downTime=0L
    @Before fun launch() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        device=UiDevice.getInstance(instrumentation)
        cancelInjectedTouch()
        SettingsRepository(instrumentation.targetContext).save(InstrumentSettings())
        scenario=ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity {activity=it}
        await {activity.diagnostics.running}
        assertTrue(device.wait(Until.hasObject(By.descContains("连续音高触摸条")),10000))
    }
    @After fun finish() {cancelInjectedTouch();scenario.close()}
    private fun cancelInjectedTouch() {
        val now=SystemClock.uptimeMillis()
        val event=MotionEvent.obtain(now,now,MotionEvent.ACTION_CANCEL,0f,0f,0)
        event.source=InputDevice.SOURCE_TOUCHSCREEN
        try {InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event,true)}
        finally {event.recycle()}
    }
    private fun await(timeout: Long=10000,predicate: () -> Boolean) {
        val deadline=SystemClock.elapsedRealtime()+timeout
        while(!predicate() && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(20)
        assertTrue("Condition timed out",predicate())
    }
    private fun activateAccessibleButton(text: String) {
        val pending=java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        pending.add(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
        while(pending.isNotEmpty()) {
            val node=pending.removeFirst()
            if(node.text?.toString()==text) {
                var button: android.view.accessibility.AccessibilityNodeInfo?=node
                while(button!=null && !button.isClickable) button=button.parent
                assertNotNull(button);assertTrue(button!!.isEnabled)
                assertTrue(button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                return
            }
            for(i in 0 until node.childCount) node.getChild(i)?.let {pending.add(it)}
        }
        fail("Missing accessible button: $text")
    }
    private fun point(pitch: Boolean,normalized: Float=.5f): PointF {
        val rect=device.findObject(By.descContains("连续音高触摸条")).visibleBounds
        val g=activity.touchController.geometry
        return PointF(rect.left+g.centerX,rect.top+if(pitch) g.pitchTop+normalized*(g.pitchBottom-g.pitchTop)
            else g.headY+(normalized-.5f)*g.headRadius)
    }
    private fun send(action: Int,ids: IntArray,points: Array<PointF>) {
        if(action==MotionEvent.ACTION_DOWN) downTime=SystemClock.uptimeMillis()
        val props=Array(ids.size) {i->MotionEvent.PointerProperties().apply {id=ids[i];toolType=MotionEvent.TOOL_TYPE_FINGER}}
        val coords=Array(ids.size) {i->MotionEvent.PointerCoords().apply {x=points[i].x;y=points[i].y;pressure=1f;size=1f}}
        val event=MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),action,ids.size,props,coords,
            0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
        try {assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event,true))}
        finally {event.recycle()}
    }
    @Test fun headOpensForVolumeAndCannotStealPitch() {
        val pitch=point(true,.45f);val closed=point(false,.5f);val open=point(false,0f)
        send(MotionEvent.ACTION_DOWN,intArrayOf(7),arrayOf(pitch))
        await {activity.parameters.gate && activity.diagnostics.peak>.001f}
        SystemClock.sleep(150)
        val closedPeak=activity.diagnostics.peak
        val frequency=activity.parameters.targetFrequency
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),intArrayOf(7,19),arrayOf(pitch,closed))
        send(MotionEvent.ACTION_MOVE,intArrayOf(7,19),arrayOf(pitch,open))
        assertEquals(frequency,activity.parameters.targetFrequency,.01f)
        assertEquals(7,activity.touchController.pitchPointerId)
        await {activity.diagnostics.mouthOpen>.98f}
        assertTrue(activity.diagnostics.peak>closedPeak*1.2f)
        SystemClock.sleep(100)
        device.takeScreenshot(File(activity.getExternalFilesDir(null),"mouth-open.png"))
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),intArrayOf(7,19),arrayOf(pitch,open))
        assertTrue(activity.parameters.gate)
        await {activity.diagnostics.mouthOpen<.02f}
        send(MotionEvent.ACTION_UP,intArrayOf(7),arrayOf(pitch))
        await {activity.diagnostics.peak==0f}
        assertFalse(activity.parameters.gate)
        assertTrue(activity.diagnostics.running) // Track stays alive between notes.
    }
    @Test fun tenSecondHoldAndFastGlidesKeepAudioAlive() {
        val start=point(true,.25f)
        send(MotionEvent.ACTION_DOWN,intArrayOf(0),arrayOf(start))
        await {activity.diagnostics.peak>.001f}
        val frequency=activity.parameters.targetFrequency
        SystemClock.sleep(10000)
        assertEquals(frequency,activity.diagnostics.frequency,.1f)
        val before=activity.diagnostics.underruns
        for(i in 0..79) {
            send(MotionEvent.ACTION_MOVE,intArrayOf(0),arrayOf(point(true,(i%20)/19f)))
            SystemClock.sleep(8)
        }
        send(MotionEvent.ACTION_UP,intArrayOf(0),arrayOf(start))
        await {activity.diagnostics.peak==0f}
        assertFalse(activity.diagnostics.error)
        val report="rate=${activity.diagnostics.sampleRate}, block=${activity.diagnostics.burstFrames}, nativeBurst=${activity.diagnostics.nativeBurstFrames}, buffer=${activity.diagnostics.bufferFrames}, underrunsBefore=$before, underrunsAfter=${activity.diagnostics.underruns}, renderLoad=${activity.diagnostics.renderLoad}, lowLatency=${activity.diagnostics.lowLatency}"
        File(activity.getExternalFilesDir(null),"device-audio-metrics.txt").writeText(report)
        android.util.Log.i("TadpoleMetrics",report)
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {putString("stream","\nTadpole audio metrics: $report\n")})
        // VM scheduling can underrun; report it honestly instead of asserting hardware latency.
        assertTrue(activity.diagnostics.running)
    }
    @Test fun backgroundReleasesTrackAndForegroundRequiresFreshTouch() {
        val start=point(true,.5f)
        send(MotionEvent.ACTION_DOWN,intArrayOf(0),arrayOf(start))
        await {activity.diagnostics.peak>.001f}
        scenario.moveToState(Lifecycle.State.CREATED)
        await { !activity.diagnostics.running }
        assertFalse(activity.parameters.gate)
        assertFalse(activity.parameters.enabled)
        scenario.moveToState(Lifecycle.State.RESUMED)
        await {activity.diagnostics.running}
        assertFalse(activity.parameters.gate)
        assertEquals(0f,activity.diagnostics.peak,0f)
        send(MotionEvent.ACTION_UP,intArrayOf(0),arrayOf(point(true,.5f)))
        send(MotionEvent.ACTION_DOWN,intArrayOf(2),arrayOf(point(true,.65f)))
        await {activity.parameters.gate && activity.diagnostics.peak>.001f}
        send(MotionEvent.ACTION_UP,intArrayOf(2),arrayOf(point(true,.65f)))
        await {activity.diagnostics.peak==0f}
    }
    @Test fun downTheStemRaisesPitchAndHorizontalMotionDoesNotChangeIt() {
        val start=point(true,.2f)
        send(MotionEvent.ACTION_DOWN,intArrayOf(0),arrayOf(start))
        val frequency=activity.parameters.targetFrequency
        val right=PointF(start.x+activity.touchController.geometry.width*.35f,start.y)
        send(MotionEvent.ACTION_MOVE,intArrayOf(0),arrayOf(right))
        assertEquals(frequency,activity.parameters.targetFrequency,.01f)
        send(MotionEvent.ACTION_MOVE,intArrayOf(0),arrayOf(point(true,.85f)))
        assertTrue(activity.parameters.targetFrequency>frequency*2)
        send(MotionEvent.ACTION_MOVE,intArrayOf(0),arrayOf(point(true,.1f)))
        assertTrue(activity.parameters.targetFrequency<frequency)
        send(MotionEvent.ACTION_CANCEL,intArrayOf(0),arrayOf(right))
        await {activity.diagnostics.peak==0f}
        assertFalse(activity.parameters.gate)
    }
    @Test fun noRuntimePermissionsAreRequested() {
        @Suppress("DEPRECATION")
        val info=activity.packageManager.getPackageInfo(activity.packageName,android.content.pm.PackageManager.GET_PERMISSIONS)
        val permissions=info.requestedPermissions ?: emptyArray()
        assertTrue(permissions.isEmpty())
    }
    @Test fun transientAudioFocusLossReleasesAndGainDoesNotResumeOldNote() {
        val manager=activity.getSystemService(android.media.AudioManager::class.java)
        val competing=android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener({},android.os.Handler(android.os.Looper.getMainLooper())).build()
        send(MotionEvent.ACTION_DOWN,intArrayOf(0),arrayOf(point(true,.5f)))
        await {activity.diagnostics.peak>.001f}
        try {
            scenario.onActivity {assertEquals(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED,manager.requestAudioFocus(competing))}
            await {!activity.diagnostics.running}
            assertFalse(activity.parameters.gate)
            assertFalse(activity.parameters.enabled)
        } finally {
            // Focus loss cancels the app's gesture, but the injected physical
            // pointer still needs its UP before another UI test starts tapping.
            send(MotionEvent.ACTION_UP,intArrayOf(0),arrayOf(point(true,.5f)))
            scenario.onActivity {manager.abandonAudioFocusRequest(competing)}
        }
        await {activity.diagnostics.running}
        assertFalse(activity.parameters.gate)
        assertEquals(0f,activity.diagnostics.peak,0f)
    }
    @Test fun legacyEffectsAreRemovedDuringSettingsMigration() {
        val prefs=activity.getSharedPreferences("instrument_settings",android.content.Context.MODE_PRIVATE)
        prefs.edit().putInt("schema",1).putString("waveform","SAW").putString("vibrato","HIGH")
            .putString("control","ONE_HAND").putFloat("mouth",1.5f).putFloat("minimum",55f)
            .putFloat("volume",.42f).commit()
        val migrated=SettingsRepository(activity).load()
        assertEquals(PitchRange.MID,migrated.range)
        assertEquals(PitchRange.MID.topHz,migrated.calibration.topHz,.001f)
        assertEquals(PitchRange.MID.bottomHz,migrated.calibration.bottomHz,.001f)
        assertEquals(.42f,migrated.masterVolume,0f)
        assertEquals(4,prefs.getInt("schema",0))
        assertFalse(prefs.contains("mouth"));assertFalse(prefs.contains("waveform"));assertFalse(prefs.contains("vibrato"))
    }
    @Test fun rangeCalibrationsAreSavedIndependently() {
        val s=InstrumentSettings().withCalibration(PitchCalibration(215f,410f,995f))
            .withRange(PitchRange.LOW).withCalibration(PitchCalibration(103f,209f,488f))
        val repo=SettingsRepository(activity);repo.save(s)
        val loaded=SettingsRepository(activity).load()
        assertEquals(488f,loaded.low.bottomHz,0f)
        assertEquals(995f,loaded.mid.bottomHz,0f)
        assertEquals(PitchRange.HIGH.bottomHz,loaded.high.bottomHz,0f)
        assertEquals(PitchRange.LOW,loaded.range)
    }
    @Test fun previousDefaultsMigrateToMeasuredRangesButCustomValuesSurvive() {
        val prefs=activity.getSharedPreferences("instrument_settings",android.content.Context.MODE_PRIVATE)
        val edit=prefs.edit().putInt("schema",3).putFloat("volume",.42f).putBoolean("haptic",true)
            .putFloat("bassDb",3f).putFloat("trebleDb",2f).putString("range","HIGH")
        for(range in PitchRange.entries) {
            val scale=when(range) {PitchRange.LOW->.5f;PitchRange.MID->1f;PitchRange.HIGH->2f}
            val top=213f*scale;val bottom=1000f*scale
            edit.putFloat("${range.name}_top",top).putFloat("${range.name}_middle",kotlin.math.sqrt(top*bottom))
                .putFloat("${range.name}_bottom",bottom)
        }
        edit.commit()
        val repo=SettingsRepository(activity);val migrated=repo.load()
        for(range in PitchRange.entries) assertEquals(PitchCalibration.forRange(range),migrated.withRange(range).calibration)
        assertEquals(PitchRange.HIGH,migrated.range);assertEquals(.42f,migrated.masterVolume,0f)
        assertTrue(migrated.haptic);assertEquals(0f,migrated.bassDb,0f);assertEquals(0f,migrated.trebleDb,0f)
        assertEquals(migrated,repo.load())
        prefs.edit().putInt("schema",3).putFloat("MID_top",215f).putFloat("MID_middle",410f)
            .putFloat("MID_bottom",995f).putFloat("bassDb",4f).putFloat("trebleDb",1f).commit()
        val custom=repo.load()
        assertEquals(PitchCalibration(215f,410f,995f),custom.mid)
        assertEquals(4f,custom.bassDb,0f);assertEquals(1f,custom.trebleDb,0f)
    }
    @Test fun everyRangePlaysItsFullMeasuredEndpointsOnTheAudioTrack() {
        for(range in PitchRange.entries) {
            scenario.onActivity {activity.touchController.applySettings(InstrumentSettings().withRange(range))}
            send(MotionEvent.ACTION_DOWN,intArrayOf(7),arrayOf(point(true,0f)))
            await {kotlin.math.abs(activity.diagnostics.frequency-range.topHz)<1f && activity.diagnostics.peak>.0001f}
            send(MotionEvent.ACTION_MOVE,intArrayOf(7),arrayOf(point(true,1f)))
            await {kotlin.math.abs(activity.diagnostics.frequency-range.bottomHz)<1f && activity.diagnostics.peak>.0001f}
            send(MotionEvent.ACTION_UP,intArrayOf(7),arrayOf(point(true,1f)))
            await {activity.diagnostics.peak==0f}
        }
        assertFalse(activity.diagnostics.error)
        assertEquals(0,activity.diagnostics.underruns)
    }
    @Test fun calibrationEditorAppliesAndSurvivesActivityRecreation() {
        device.findObject(By.desc("打开设置")).click()
        assertTrue(device.wait(Until.hasObject(By.text("设置")),5000))
        device.findObject(By.text("音域校准")).click()
        device.swipe(device.displayWidth*9/10,device.displayHeight*8/10,device.displayWidth*9/10,device.displayHeight*2/10,40)
        device.waitForIdle()
        val fields=device.findObjects(By.clazz("android.widget.EditText"))
        assertEquals(3,fields.size)
        fields[0].text="215"
        fields[1].text="410"
        fields[2].text="995"
        device.waitForIdle()
        // The TextView child remains enabled while its Button parent is disabled.
        // Wait for the actual clickable Button after Compose consumes text edits.
        val apply=device.wait(Until.findObject(By.clickable(true).enabled(true)
            .hasDescendant(By.text("应用当前档位标定"))),5000)
        assertNotNull("Calibration apply button did not become enabled",apply)
        // Complete the accessibility edit through the real Button's ACTION_CLICK.
        // Physical pointer mapping is checked separately by the pitch-guide tests.
        activateAccessibleButton("应用当前档位标定")
        try {await {activity.parameters.settings.mid.middleHz==410f}} catch(error: AssertionError) {
            device.takeScreenshot(File(activity.getExternalFilesDir(null),"calibration-failure.png"))
            device.dumpWindowHierarchy(File(activity.getExternalFilesDir(null),"calibration-failure.xml"))
            File(activity.getExternalFilesDir(null),"calibration-failure.txt").writeText(activity.parameters.settings.toString())
            throw error
        }
        scenario.recreate()
        scenario.onActivity {activity=it}
        await {activity.diagnostics.running}
        assertEquals(215f,activity.parameters.settings.mid.topHz,0f)
        assertEquals(410f,activity.parameters.targetFrequency,.001f)
        assertEquals(995f,activity.parameters.settings.mid.bottomHz,0f)
    }
    @Test fun visibleBlackCapsAreAboveHeadAndDeadLabelsAreAbsent() {
        val rect=device.findObject(By.descContains("连续音高触摸条")).visibleBounds
        val g=activity.touchController.geometry
        assertTrue(g.pitchBottom<g.headTop-8*g.unit)
        val screenshot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {
            val x=(rect.left+g.centerX).toInt()
            for(y in listOf(g.pitchTop+4*g.unit,g.pitchBottom-4*g.unit)) {
                val color=screenshot.getPixel(x,(rect.top+y).toInt())
                assertTrue("Both visible black caps must remain exposed",android.graphics.Color.red(color)<90)
            }
        } finally {screenshot.recycle()}
        assertNull(device.findObject(By.textContains("ORIGINAL TONE")))
        assertNull(device.findObject(By.text("原乐器音色")))
        assertNull(device.findObject(By.text("电音蝌蚪")))
    }
    @Test fun guideLabelsFollowRangeAndTouchingAMarkSoundsItsPrintedNote() {
        for((range,midi) in listOf(PitchRange.LOW to 45,PitchRange.MID to 69,PitchRange.HIGH to 93)) {
            device.findObject(By.text(range.name)).click()
            await {activity.parameters.settings.range==range}
            assertTrue(device.wait(Until.hasObject(By.descContains("${range.name} 音高标记")),5000))
            val mark=com.tadpole.instrument.input.PitchGuide.marks(activity.parameters.settings).single {it.midi==midi}
            val target=point(true,mark.position)
            send(MotionEvent.ACTION_DOWN,intArrayOf(7),arrayOf(target))
            await {kotlin.math.abs(activity.diagnostics.frequency-mark.frequency)<.1f}
            assertTrue(device.wait(Until.hasObject(By.text("${mark.label} · 准")),5000))
            send(MotionEvent.ACTION_UP,intArrayOf(7),arrayOf(target))
            await {activity.diagnostics.peak==0f}
        }
        device.findObject(By.desc("打开设置")).longClick()
        assertTrue(device.wait(Until.hasObject(By.textContains("ENGINE / DEBUG")),5000))
        device.findObject(By.desc("打开设置")).longClick()
        assertTrue(device.wait(Until.gone(By.textContains("ENGINE / DEBUG")),5000))
    }
    @Test fun toneCompensationCanBeZeroedAndSaved() {
        val custom=InstrumentSettings().withTone(4.5f,1.5f)
        val repo=SettingsRepository(activity);repo.save(custom)
        assertEquals(4.5f,repo.load().bassDb,0f)
        assertEquals(1.5f,repo.load().trebleDb,0f)
        repo.save(custom.withTone(0f,0f))
        assertEquals(0f,repo.load().bassDb,0f)
        assertEquals(0f,repo.load().trebleDb,0f)
    }
}
