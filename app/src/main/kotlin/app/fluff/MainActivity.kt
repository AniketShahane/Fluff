package app.fluff

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.fluff.integrations.whoop.WhoopException
import app.fluff.ui.nav.FluffRoot
import app.fluff.ui.theme.Fluff
import app.fluff.ui.theme.FluffTheme
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container get() = (application as FluffApp).container

    /** null until the stored profile has been read; keeps the splash up meanwhile. */
    private val onboarded = MutableStateFlow<Boolean?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { onboarded.value == null }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Fluff.Night.copy(alpha = 0.6f).toArgb()),
        )
        lifecycleScope.launch { onboarded.value = container.prefs.profile.first().onboarded }
        handleIntent(intent)
        setContent {
            val ready by onboarded.collectAsStateWithLifecycle()
            FluffTheme {
                ready?.let { FluffRoot(onboarded = it) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Completes WHOOP sign-in when the browser redirects to fluff://oauth/whoop. */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "fluff" || data.host != "oauth" || data.path != "/whoop") return
        intent.data = null
        val c = container
        c.appScope.launch {
            try {
                c.whoop.handleRedirect(data.getQueryParameter("code"), data.getQueryParameter("state"), data.getQueryParameter("error"))
                c.messages.emit("WHOOP connected. Syncing…")
                val err = c.sync.sync()
                runCatching { c.engine.onNewSignals() }
                c.messages.emit(err ?: "WHOOP data synced")
            } catch (e: WhoopException) {
                c.messages.emit(e.message ?: "WHOOP sign-in failed")
            } catch (e: Exception) {
                c.messages.emit("WHOOP sign-in failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}
