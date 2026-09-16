package com.tadpole.instrument

import android.graphics.*
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.roundToInt

/** Render the installed application's actual resources using Android's renderer. */
@RunWith(AndroidJUnit4::class)
class LauncherIconTest {
    @Test fun iconFitsAdaptiveSafeAreaAndRendersWithCommonMasks() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val icon=context.packageManager.getApplicationIcon(context.packageName)
        assertTrue(icon is AdaptiveIconDrawable)
        icon as AdaptiveIconDrawable
        val out=context.getExternalFilesDir(null)!!
        fun save(bitmap: Bitmap,name: String) {
            File(out,name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
        val foreground=context.getDrawable(R.drawable.ic_launcher_foreground)!!
        val source=Bitmap.createBitmap(864,864,Bitmap.Config.ARGB_8888)
        foreground.setBounds(0,0,864,864);foreground.draw(Canvas(source))
        var ink=0
        for(y in 0 until 864) for(x in 0 until 864) if(Color.alpha(source.getPixel(x,y))>32) {
            val dx=x+0.5-432;val dy=y+0.5-432
            assertTrue("Foreground must fit the 66dp safe circle",dx*dx+dy*dy<=(33*8+1.0)*(33*8+1.0))
            ink++
        }
        assertTrue(ink>30000)
        save(source,"launcher-foreground.png");source.recycle()
        val size=432
        for(round in listOf(true,false)) {
            val bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(bitmap);val clip=Path()
            if(round) clip.addCircle(size/2f,size/2f,size/2f,Path.Direction.CW)
            else clip.addRoundRect(0f,0f,size.toFloat(),size.toFloat(),size*.23f,size*.23f,Path.Direction.CW)
            canvas.clipPath(clip)
            val inset=(size*AdaptiveIconDrawable.getExtraInsetFraction()).roundToInt()
            for(layer in listOf(icon.background,icon.foreground)) {
                layer.setBounds(-inset,-inset,size+inset,size+inset);layer.draw(canvas)
            }
            save(bitmap,if(round) "launcher-round.png" else "launcher-rounded-square.png")
            bitmap.recycle()
        }
        val mono=context.getDrawable(R.drawable.ic_launcher_monochrome)!!
        val bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
        mono.setBounds(0,0,size,size);mono.draw(Canvas(bitmap))
        save(bitmap,"launcher-monochrome.png");bitmap.recycle()
    }
}
