package app.fluff

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.fluff.ui.components.PrimaryButton
import app.fluff.ui.theme.Fluff
import app.fluff.ui.theme.FluffTheme

/** Shown by Health Connect when the user taps "privacy policy" on Fluff's permission screen. */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FluffTheme {
                Surface(Modifier.fillMaxSize(), color = Fluff.Night) {
                    Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
                        Text("How Fluff uses your health data", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(16.dp))
                        PRIVACY.forEach { (title, body) ->
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(body, style = MaterialTheme.typography.bodyMedium, color = Fluff.TextDim)
                            Spacer(Modifier.height(16.dp))
                        }
                        PrimaryButton("Got it", onClick = { finish() })
                    }
                }
            }
        }
    }

    companion object {
        val PRIVACY = listOf(
            "What we read" to "Heart rate variability, resting heart rate, sleep sessions and workouts. Fluff turns these into a daily readiness score and accounts for your other sports when it plans strength work.",
            "What we write" to "When you finish a Fluff session, it is saved to Health Connect as a strength-training workout so your other apps see it. You can turn this off in Profile.",
            "Where it lives" to "Everything stays on your phone in Fluff's private storage. There is no Fluff account and no Fluff server.",
            "The AI coach" to "If you add your own Claude API key, Fluff sends Anthropic a compact summary for planning: your readiness score and its factors, training goal, recent lifts and activities. It never sends your name. Turn the coach off any time and Fluff plans entirely on-device.",
            "Your control" to "Revoke access in Health Connect settings at any time. Deleting Fluff deletes all of its data.",
        )
    }
}
