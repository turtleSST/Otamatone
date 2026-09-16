package com.tadpole.instrument

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Black-box check of the exact signed/minified APK; no app classes or R IDs.
 * scripts/verify_release.ps1 signs the test runner with the release key. */
@RunWith(AndroidJUnit4::class)
class ReleaseSmokeTest {
    @Test fun signedReleasePlaysAllRangesAndReturnsSilentFromBackground() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("releaseSmoke")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        assertEquals(0,context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        val device=UiDevice.getInstance(instrumentation)
        val oldIdle=Configurator.getInstance().waitForIdleTimeout
        Configurator.getInstance().waitForIdleTimeout=0
        var downTime=0L
        fun pointer(action: Int,x: Float,y: Float) {
            if(action==MotionEvent.ACTION_DOWN) downTime=SystemClock.uptimeMillis()
            val event=MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),action,x,y,0)
            event.source=InputDevice.SOURCE_TOUCHSCREEN
            try {instrumentation.uiAutomation.injectInputEvent(event,true)} finally {event.recycle()}
        }
        fun await(description: String,condition: ()->Boolean) {
            val end=SystemClock.elapsedRealtime()+10000
            while(!condition() && SystemClock.elapsedRealtime()<end) SystemClock.sleep(30)
            assertTrue(description,condition())
        }
        fun panel()=device.findObject(By.textContains("ENGINE / DEBUG"))?.text.orEmpty()
        fun number(pattern: String,text: String)=Regex(pattern).find(text)?.groupValues?.get(1)?.toFloatOrNull()
        val records=ArrayList<String>()
        try {
            val launch=context.packageManager.getLaunchIntentForPackage(context.packageName)!!
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(launch)
            assertTrue(device.wait(Until.hasObject(By.descContains("连续音高触摸条")),15000))
            await("Audio engine did not finish preparing") {!device.hasObject(By.text("正在准备音色"))}
            device.findObject(By.text("MID")).click()
            val surface=device.findObject(By.descContains("连续音高触摸条")).visibleBounds
            val x=surface.exactCenterX()
            var start=0;var finish=0
            await("Visible pitch strip not found") {
                val bitmap=instrumentation.uiAutomation.takeScreenshot()
                try {
                    var run=-1;var longest=0
                    for(y in surface.top until surface.bottom) {
                        val color=bitmap.getPixel(x.toInt(),y)
                        val dark=abs(Color.red(color)-51)<=4 && abs(Color.green(color)-44)<=4 && abs(Color.blue(color)-40)<=4
                        if(dark && run<0) run=y
                        if((!dark || y==surface.bottom-1) && run>=0) {
                            if(y-run>longest) {longest=y-run;start=run;finish=y-1}
                            run=-1
                        }
                    }
                    longest>100
                } finally {bitmap.recycle()}
            }
            device.findObject(By.desc("打开设置")).longClick()
            await("Debug diagnostics unavailable") {panel().isNotEmpty()}
            val ranges=listOf("LOW" to floatArrayOf(54.4914f,98.7397f,292.1801f),
                "MID" to floatArrayOf(203.7904f,388.3689f,1057.6378f),
                "HIGH" to floatArrayOf(806.2625f,1446.4551f,4443.6792f))
            for((range,hz) in ranges) {
                device.findObject(By.text(range)).click()
                pointer(MotionEvent.ACTION_DOWN,x,finish-4f)
                // Acquire below the diagnostics panel, then move the captured finger.
                for((y,expected) in listOf(finish+8f to hz[2],(start+finish)*.5f to hz[1],start-8f to hz[0])) {
                    pointer(MotionEvent.ACTION_MOVE,x,y)
                    await("$range did not render $expected Hz") {
                        val text=panel();val pitch=number("Pitch: ([0-9.]+)",text);val peak=number("Peak: ([0-9.]+)",text)
                        pitch!=null && abs(pitch-expected)<5f && peak!=null && peak>.001f
                    }
                    records.add("$range target=$expected: "+panel().replace('\n',' '))
                }
                pointer(MotionEvent.ACTION_UP,x,start-8f)
                await("$range did not release to silence") {number("Peak: ([0-9.]+)",panel())==0f}
            }
            pointer(MotionEvent.ACTION_DOWN,x,finish-4f)
            await("Note did not start before backgrounding") {(number("Peak: ([0-9.]+)",panel()) ?: 0f)>.001f}
            device.pressHome();pointer(MotionEvent.ACTION_UP,x,finish-4f)
            context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("Old note resumed after returning to the app") {number("Peak: ([0-9.]+)",panel())==0f}
            device.findObject(By.desc("打开设置")).longClick()
            assertTrue(device.wait(Until.gone(By.textContains("ENGINE / DEBUG")),5000))
            device.findObject(By.text("MID")).click()
            File(context.getExternalFilesDir(null),"release-smoke.txt").writeText(records.joinToString("\n"))
            device.takeScreenshot(File(context.getExternalFilesDir(null),"release-main.png"))
        } finally {
            pointer(MotionEvent.ACTION_CANCEL,0f,0f)
            Configurator.getInstance().waitForIdleTimeout=oldIdle
        }
    }
}
