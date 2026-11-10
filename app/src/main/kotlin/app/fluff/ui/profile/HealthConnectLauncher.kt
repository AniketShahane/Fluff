package app.fluff.ui.profile

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.health.connect.client.PermissionController
import app.fluff.integrations.healthconnect.HcAvailability
import app.fluff.ui.theme.Fluff
import androidx.compose.ui.graphics.toArgb

/** Returns a function that asks for Health Connect access, or sends the user to install/update it. */
@Composable
fun rememberHealthConnectRequest(connections: Connections, onResult: (Set<String>) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { onResult(it) }
    return {
        when (connections.hcAvailability) {
            HcAvailability.AVAILABLE -> launcher.launch(connections.hcAllPermissions)
            else -> {
                // Health Connect is missing or outdated: open its Play Store page.
                val uri = "market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding".toUri()
                try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }
}

fun openCustomTab(ctx: android.content.Context, uri: Uri) {
    val colors = CustomTabColorSchemeParams.Builder().setToolbarColor(Fluff.Night.toArgb()).build()
    CustomTabsIntent.Builder().setDefaultColorSchemeParams(colors).setShowTitle(true).build().launchUrl(ctx, uri)
}
