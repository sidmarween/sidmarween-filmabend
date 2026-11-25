package de.marween.filmabend

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.mutableIntStateOf
import de.marween.filmabend.ui.FilmabendApp
import de.marween.filmabend.ui.FilmabendTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val startTab = mutableIntStateOf(0)
    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startTab.intValue = intent?.getIntExtra("tab", 0) ?: 0
        handleShare(intent)
        if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        SuggestionWorker.schedule(this)
        setContent {
            FilmabendTheme {
                FilmabendApp(vm, startTab.intValue) { startTab.intValue = it }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra("tab")) startTab.intValue = intent.getIntExtra("tab", 0)
        handleShare(intent)
    }

    /** Aus dem Browser geteilte Seite / Liste → Sammlungen → Einlesen */
    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return
        vm.sharedImport.value = text
        startTab.intValue = 1
    }
}
