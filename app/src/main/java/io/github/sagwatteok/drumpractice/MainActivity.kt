package io.github.sagwatteok.drumpractice

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.lifecycle.ViewModelProvider
import io.github.sagwatteok.drumpractice.feature.diagnostics.PracticeScreen
import io.github.sagwatteok.drumpractice.feature.diagnostics.PracticeViewModel

class MainActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[PracticeViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                PracticeScreen(model)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.refresh()
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            model.audio.stop()
            model.stopMock()
        }
        super.onStop()
    }
}
