package com.tadpole.instrument

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tadpole.instrument.audio.*
import com.tadpole.instrument.input.InstrumentTouchController
import com.tadpole.instrument.model.SettingsRepository
import com.tadpole.instrument.ui.InstrumentScreen

class MainActivity : ComponentActivity() {
    val parameters=AudioParameters()
    val diagnostics=AudioDiagnostics()
    val touchController=InstrumentTouchController(parameters)
    lateinit var audioEngine: AudioEngine; private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        volumeControlStream=android.media.AudioManager.STREAM_MUSIC
        val repository=SettingsRepository(this)
        touchController.applySettings(repository.load())
        audioEngine=AudioEngine(this,parameters,diagnostics)
        audioEngine.onCancelGesture={touchController.cancel()}
        setContent { InstrumentScreen(parameters,diagnostics,touchController,repository,audioEngine::requestPlaying) }
    }
    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        audioEngine.resume()
    }
    override fun onPause() {
        touchController.cancel()
        audioEngine.pause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if(!hasFocus) touchController.cancel()
    }
    override fun onDestroy() {audioEngine.close();super.onDestroy()}
}
