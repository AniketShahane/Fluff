package app.fluff.ui.exercise

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Healing
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.fluff.core.library.ExerciseLibrary
import app.fluff.core.library.ExerciseVideos
import app.fluff.core.library.FormVideo
import app.fluff.core.model.Exercise
import app.fluff.core.model.MovementType
import app.fluff.core.planner.Insight
import app.fluff.core.planner.InsightKind
import app.fluff.core.planner.InsightTone
import app.fluff.ui.components.Eyebrow
import app.fluff.ui.components.Pill
import app.fluff.ui.theme.Fluff

fun movementColor(t: MovementType): Color = when (t) {
    MovementType.COMPOUND -> Fluff.Lime
    MovementType.ISOLATION -> Fluff.Violet
    MovementType.CORE -> Fluff.Sky
    MovementType.CARRY -> Fluff.Amber
    MovementType.CARDIO -> Fluff.Coral
    MovementType.MOBILITY -> Fluff.Mint
}

/** Small coloured label: Compound, Isolation, Core... */
@Composable
fun MovementTag(type: MovementType, modifier: Modifier = Modifier) {
    val c = movementColor(type)
    Row(
        modifier.clip(CircleShape).background(c.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(5.dp))
        Text(type.label, style = MaterialTheme.typography.labelSmall, color = c)
    }
}

private fun insightIcon(k: InsightKind) = when (k) {
    InsightKind.PROGRESS -> Icons.Rounded.TrendingUp
    InsightKind.LAST_TIME -> Icons.Rounded.History
    InsightKind.RECORD -> Icons.Rounded.EmojiEvents
    InsightKind.RECOVERY -> Icons.Rounded.Bolt
    InsightKind.EFFORT -> Icons.Rounded.Speed
    InsightKind.JOINT -> Icons.Rounded.Healing
    InsightKind.FIRST_TIME -> Icons.Rounded.Lightbulb
}

fun insightColor(t: InsightTone): Color = when (t) {
    InsightTone.POSITIVE -> Fluff.Mint
    InsightTone.NEUTRAL -> Fluff.TextDim
    InsightTone.CAUTION -> Fluff.Amber
}

/** One reason behind a prescription, with an icon that says what kind of reason it is. */
@Composable
fun InsightLine(insight: Insight, modifier: Modifier = Modifier, maxLines: Int = 2) {
    val c = insightColor(insight.tone)
    Row(modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Icon(insightIcon(insight.kind), null, tint = c, modifier = Modifier.padding(top = 2.dp).size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            insight.text, style = MaterialTheme.typography.bodySmall,
            color = if (insight.tone == InsightTone.NEUTRAL) Fluff.TextDim else c.copy(alpha = 0.95f),
            maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        )
    }
}

fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No browser or YouTube app: nothing sensible to do.
    }
}

/**
 * Plays a YouTube Short inside the app. YouTube requires embedders to identify themselves through
 * the Referer header, so the page is given an https origin named after the app id (the format
 * YouTube documents for apps) and the iframe sends a strict-origin referrer.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FormVideoPlayer(video: FormVideo, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(
        modifier.clip(RoundedCornerShape(20.dp)).background(Color.Black).semantics { contentDescription = "Form video: ${video.title}" },
        contentAlignment = Alignment.Center,
    ) {
        if (LocalInspectionMode.current || isRobolectric()) {
            // Previews and screenshot tests can't run a WebView; show a poster instead.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.PlayCircle, null, tint = Color.White, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(8.dp))
                Text(video.title, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
            }
        } else {
            val origin = "https://${context.packageName}"
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    WebView(c).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                // Anything that tries to leave the player (YouTube logo, "watch on YouTube") opens outside.
                                if (request.isForMainFrame) {
                                    openUrl(c, request.url.toString())
                                    return true
                                }
                                return false
                            }
                        }
                        loadDataWithBaseURL("$origin/", embedHtml(video.youtubeId, origin), "text/html", "utf-8", null)
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}

private fun isRobolectric(): Boolean = android.os.Build.FINGERPRINT == "robolectric"

internal fun embedHtml(id: String, origin: String): String = """
<!doctype html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="strict-origin-when-cross-origin">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{position:absolute;inset:0;width:100%;height:100%;border:0}</style>
</head><body>
<iframe src="https://www.youtube-nocookie.com/embed/$id?playsinline=1&rel=0&loop=1&playlist=$id&origin=$origin&widget_referrer=$origin"
 title="Form video" allow="autoplay; encrypted-media; picture-in-picture; fullscreen"
 referrerpolicy="strict-origin-when-cross-origin" allowfullscreen></iframe>
</body></html>
""".trimIndent()

/**
 * Everything about one exercise in a sheet: form video, the cue, muscles, movement type and, when
 * it's in today's plan, why.
 */
@Composable
fun ExerciseDetail(exerciseId: String, insights: List<Insight> = emptyList(), modifier: Modifier = Modifier) {
    val ex = ExerciseLibrary[exerciseId] ?: return
    val video = ExerciseVideos[exerciseId]
    val context = LocalContext.current
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            MovementTag(ex.movementType)
            Text(ex.pattern.label, style = MaterialTheme.typography.labelMedium, color = Fluff.TextFaint)
            Text("·", color = Fluff.TextFaint)
            Text(if (ex.equipment.isEmpty()) "Bodyweight" else ex.equipment.joinToString { it.label }, style = MaterialTheme.typography.labelMedium, color = Fluff.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        Text(ex.name, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(14.dp))
        if (video != null) {
            FormVideoPlayer(video, Modifier.fillMaxWidth(0.72f).align(Alignment.CenterHorizontally).heightIn(max = 380.dp).aspectRatio(9f / 16f))
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.align(Alignment.CenterHorizontally).clip(CircleShape).clickable(role = Role.Button) { openUrl(context, video.watchUrl) }.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${video.channel} on YouTube", style = MaterialTheme.typography.labelMedium, color = Fluff.TextDim)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Rounded.OpenInNew, null, tint = Fluff.TextDim, modifier = Modifier.size(14.dp))
            }
        } else {
            Pill("Find a demo on YouTube", icon = Icons.Rounded.PlayCircle, onClick = { openUrl(context, ExerciseVideos.searchUrl(ex.name)) })
        }
        Spacer(Modifier.height(18.dp))
        Eyebrow("How to")
        Spacer(Modifier.height(4.dp))
        Text(ex.cue, style = MaterialTheme.typography.bodyLarge)
        if (ex.unilateral) Text("Work one side at a time.", style = MaterialTheme.typography.bodyMedium, color = Fluff.TextDim)
        Spacer(Modifier.height(16.dp))
        Eyebrow("Works")
        Spacer(Modifier.height(4.dp))
        Text(ex.primary.joinToString { it.label }, style = MaterialTheme.typography.bodyLarge)
        if (ex.secondary.isNotEmpty()) Text("Also: " + ex.secondary.joinToString { it.label.lowercase() }, style = MaterialTheme.typography.bodyMedium, color = Fluff.TextDim)
        Text(ex.movementType.blurb + ".", style = MaterialTheme.typography.bodySmall, color = Fluff.TextFaint, modifier = Modifier.padding(top = 4.dp))
        if (insights.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Eyebrow("Why it's in today's plan")
            Spacer(Modifier.height(6.dp))
            insights.forEach { InsightLine(it, maxLines = 3) }
        }
    }
}

/** Searchable, filterable list of every exercise the user can do, used to add one mid-workout. */
@Composable
fun ExercisePicker(
    title: String,
    subtitle: String,
    exercises: List<Exercise>,
    onPick: (Exercise) -> Unit,
    modifier: Modifier = Modifier,
    initialType: MovementType? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(initialType) }
    val types = remember(exercises) { MovementType.entries.filter { t -> exercises.any { it.movementType == t } } }
    val shown = exercises.filter { (type == null || it.movementType == type) && (query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) || it.primary.any { m -> m.label.contains(query.trim(), ignoreCase = true) }) }
    Column(modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Fluff.TextDim)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("Search by name or muscle") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Fluff.Line),
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("All", selected = type == null, onClick = { type = null })
            types.forEach { t -> Pill(t.label, selected = type == t, onClick = { type = if (type == t) null else t }) }
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            items(shown, key = { it.id }) { ex ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onPick(ex) }.padding(vertical = 10.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(ex.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MovementTag(ex.movementType)
                            Spacer(Modifier.width(6.dp))
                            Text(ex.primary.joinToString { it.label }, style = MaterialTheme.typography.bodySmall, color = Fluff.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Box(Modifier.size(36.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Fluff.CardHigh, Fluff.Card))), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Add, "Add ${ex.name}", tint = Fluff.Lime, modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (shown.isEmpty()) item { Text("Nothing matches.", color = Fluff.TextFaint, modifier = Modifier.padding(16.dp)) }
        }
        Spacer(Modifier.height(12.dp))
    }
}
