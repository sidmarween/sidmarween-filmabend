package de.marween.filmabend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import de.marween.filmabend.Catalog

val Gold = Color(0xFFF5B841)
val Rose = Color(0xFFE85D75)
val Bg = Color(0xFF14111C)
val CardBg = Color(0xFF221C2E)

private val scheme = darkColorScheme(
    primary = Gold,
    onPrimary = Color(0xFF221800),
    secondary = Rose,
    onSecondary = Color.White,
    background = Bg,
    surface = Bg,
    surfaceVariant = CardBg,
    surfaceContainer = CardBg,
    surfaceContainerHigh = Color(0xFF2B2439),
    surfaceContainerLow = Color(0xFF1B1625),
    onBackground = Color(0xFFF1ECF7),
    onSurface = Color(0xFFF1ECF7),
    onSurfaceVariant = Color(0xFFBDB3CC),
)

@Composable
fun FilmabendTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun Poster(path: String?, type: String, modifier: Modifier = Modifier, corner: Dp = 10.dp) {
    Box(
        modifier
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(corner))
            .background(CardBg),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (type == "tv") Icons.Filled.Tv else Icons.Filled.Movie, null,
            tint = Color.White.copy(alpha = 0.25f), modifier = Modifier.size(36.dp)
        )
        if (path != null) {
            AsyncImage(
                model = path, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** Kleines rundes Abzeichen mit Bewertung, z. B. „8“. */
@Composable
fun RatingBadge(value: String, color: Color, label: String? = null) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.92f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (label != null) Text(label, fontSize = 10.sp, color = Color.Black.copy(alpha = 0.7f), fontWeight = FontWeight.Bold)
        if (label != null) Text(" ", fontSize = 10.sp)
        Text(value, fontSize = 12.sp, color = Color.Black, fontWeight = FontWeight.Bold)
    }
}

fun ratingColor(r: Double): Color = when {
    r >= 8.5 -> Color(0xFF5BD68A)
    r >= 7 -> Color(0xFFA6DB5B)
    r >= 5 -> Gold
    r >= 3 -> Color(0xFFF28C4B)
    else -> Rose
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChipRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)
    ) {
        items(options.size) { i ->
            FilterChip(selected = i == selected, onClick = { onSelect(i) }, label = { Text(options[i]) })
        }
    }
}

@Composable
fun EmptyHint(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    pose: TiwiPose = TiwiPose.Sleeping,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Tiwi(pose, Modifier.height(96.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

val TypeFilters = listOf("Alle", "Filme", "Serien")
fun typeMatches(filter: Int, type: String) = when (filter) {
    1 -> type == "movie"
    2 -> type == "tv"
    else -> true
}

/** Zeigt Text immer auf Deutsch – Englisches wird automatisch auf dem Handy übersetzt. */
@Composable
fun GermanText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodySmall,
    maxLines: Int = Int.MAX_VALUE,
) {
    var shown by androidx.compose.runtime.remember(text) { androidx.compose.runtime.mutableStateOf(text) }
    androidx.compose.runtime.LaunchedEffect(text) {
        if (de.marween.filmabend.German.looksEnglish(text)) {
            shown = runCatching { de.marween.filmabend.German.translate(text) }.getOrDefault(text)
        }
    }
    Text(shown, modifier = modifier, style = style, maxLines = maxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
}

// ---------------- Tiwi, das Maskottchen ----------------

enum class TiwiPose(val res: Int) {
    Waving(de.marween.filmabend.R.drawable.tiwi_waving),
    Investigating(de.marween.filmabend.R.drawable.tiwi_investigating),
    Sleeping(de.marween.filmabend.R.drawable.tiwi_sleeping),
    Surprised(de.marween.filmabend.R.drawable.tiwi_surprised),
    Laughing(de.marween.filmabend.R.drawable.tiwi_laughing),
}

/** Tiwi in einer Pose – leicht abgedunkelt, damit er dezent bleibt. */
@Composable
fun Tiwi(pose: TiwiPose, modifier: Modifier = Modifier, alpha: Float = 0.9f) {
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(pose.res),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        alpha = alpha,
        modifier = modifier,
    )
}

/** Ladeanzeige: Tiwi sucht mit der Lupe und wippt dabei sanft. */
@Composable
fun TiwiLoading(text: String?, modifier: Modifier = Modifier) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "tiwi")
    val bob by t.animateFloat(
        initialValue = 0f, targetValue = -6f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(700, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "bob"
    )
    Box(modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Tiwi(TiwiPose.Investigating, Modifier.height(76.dp).graphicsLayer { translationY = bob * density })
            androidx.compose.material3.LinearProgressIndicator(
                Modifier.padding(top = 12.dp).width(90.dp).clip(RoundedCornerShape(50))
            )
            if (text != null) Text(text, modifier = Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}
