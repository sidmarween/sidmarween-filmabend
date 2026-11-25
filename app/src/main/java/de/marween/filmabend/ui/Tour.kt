package de.marween.filmabend.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Erklärmodus: Tiwi führt Schritt für Schritt durch die App und hebt dabei
 * die jeweilige Stelle hervor. Startet beim ersten Öffnen automatisch und
 * lässt sich in den Einstellungen jederzeit neu starten.
 */
object TourTargets {
    val rects = mutableStateMapOf<String, Rect>()
}

/** Markiert ein Element, damit der Erklärmodus es hervorheben kann. */
fun Modifier.tourTarget(id: String): Modifier =
    onGloballyPositioned { TourTargets.rects[id] = it.boundsInRoot() }

private data class TourStep(
    val title: String,
    val text: String,
    val pose: TiwiPose,
    val tab: Int? = null,
    val target: String? = null,
)

private val steps = listOf(
    TourStep(
        "Hallo, ich bin Tiwi! 👋",
        "Ich zeige dir kurz, was Filmabend alles kann. Tippe auf „Weiter“ – oder auf „Überspringen“, wenn du dich schon auskennst.",
        TiwiPose.Waving, tab = 0
    ),
    TourStep(
        "Bibliothek",
        "Hier stehen alle eure Filme und Serien. Hakt ab, was ihr gesehen habt, setzt Titel auf die Merkliste und bewertet sie von 1 bis 10 – jeder für sich. Aus euren Bewertungen lernt die App euren Geschmack.",
        TiwiPose.Waving, tab = 0, target = "nav0"
    ),
    TourStep(
        "Suchen",
        "Mit der Lupe findest du jeden Film und jede Serie – oder alle Titel eines Schauspielers. Antippen, eintragen, fertig.",
        TiwiPose.Investigating, target = "search"
    ),
    TourStep(
        "Sammlungen",
        "Leg zu einem Stichwort eine Sammlung an – z. B. „James Bond“ oder „Disney“. Alle Filme stehen chronologisch da, und jeder hakt ab, was er schon gesehen hat.",
        TiwiPose.Investigating, tab = 1, target = "nav1"
    ),
    TourStep(
        "Neu & Trailer",
        "Der Tipp des Tages aus dem Fernsehen, neue Filme und Serien mit deutschen Trailern, die Mediatheken von ARD, ZDF & Co., DVD-Neuheiten und „Heute im TV“ – das aktuelle Fernsehprogramm. Oben findest du ein Suchfeld, und mit 👎 lehnst du ab, was dir gar nicht gefällt.",
        TiwiPose.Surprised, tab = 2, target = "nav2"
    ),
    TourStep(
        "Aktualisieren",
        "Mit diesem Knopf holst du die neuesten Trailer und Neuheiten. Beim Tipp des Tages bekommst du mit „Anderer Tipp“ weitere Vorschläge – jeweils mit Fun Fact.",
        TiwiPose.Investigating, tab = 2, target = "refresh"
    ),
    TourStep(
        "Unsere Charts",
        "Eure Bestenliste: Was hat euch am besten gefallen, was war ein Flop – und wo wart ihr euch so gar nicht einig?",
        TiwiPose.Laughing, tab = 3, target = "nav3"
    ),
    TourStep(
        "Post",
        "Hier landen Film- und Serientipps von deinem Partner. Selbst schickst du einen Tipp über den „An …“-Knopf, den es bei jedem Titel gibt.",
        TiwiPose.Waving, tab = 4, target = "nav4"
    ),
    TourStep(
        "Einstellungen",
        "Hier verbindest du deine Liste mit deinem Partner, änderst deinen Namen – und kannst mich jederzeit wieder rufen: „Erklärmodus starten“.",
        TiwiPose.Investigating, target = "settings"
    ),
    TourStep(
        "Das war's! 🍿",
        "Jetzt viel Spaß beim nächsten Filmabend. Ich bin auch sonst in der App unterwegs – halt einfach die Augen offen.",
        TiwiPose.Laughing, tab = 0
    ),
)

@Composable
fun TourOverlay(setTab: (Int) -> Unit, onDone: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    val step = steps[index]
    var origin by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(Size.Zero) }

    LaunchedEffect(index) { step.tab?.let(setTab) }

    val target = step.target?.let { TourTargets.rects[it] }?.translate(-origin)
    val pad = 8f
    val hole = target?.let { Rect(it.left - pad, it.top - pad, it.right + pad, it.bottom + pad) }
    // Sanft pulsierender Rahmen um die erklärte Stelle
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f, targetValue = 0.35f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(tween(800), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "pulse"
    )
    // Steht die Stelle unten, erscheint Tiwi oben – und umgekehrt
    val bubbleTop = hole != null && size.height > 0 && hole.center.y > size.height / 2

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.boundsInRoot().topLeft; size = it.boundsInRoot().size }
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            drawRect(Color.Black.copy(alpha = 0.72f))
            if (hole != null) {
                val r = CornerRadius(18.dp.toPx())
                drawRoundRect(Color.Transparent, hole.topLeft, hole.size, r, blendMode = BlendMode.Clear)
                drawRoundRect(Gold.copy(alpha = pulse), hole.topLeft, hole.size, r, style = Stroke(2.5.dp.toPx()))
            }
        }

        Column(
            Modifier
                .align(if (bubbleTop) Alignment.TopCenter else Alignment.BottomCenter)
                .then(if (bubbleTop) Modifier.statusBarsPadding().padding(top = 64.dp) else Modifier.navigationBarsPadding().padding(bottom = if (hole != null) 96.dp else 32.dp))
                .padding(horizontal = 16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedContent(step, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "tour") { s ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Tiwi(s.pose, Modifier.height(if (hole == null) 140.dp else 96.dp), alpha = 1f)
                    Column(
                        Modifier
                            .padding(top = 6.dp)
                            .fillMaxWidth()
                            .background(Color(0xFF1E1830), RoundedCornerShape(18.dp))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(s.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Gold)
                        Text(s.text, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                    }
                }
            }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                steps.indices.forEach { i ->
                    Box(
                        Modifier.padding(3.dp).size(if (i == index) 8.dp else 6.dp)
                            .background(if (i == index) Gold else Color.White.copy(alpha = 0.35f), CircleShape)
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (index < steps.lastIndex) TextButton(onClick = onDone) { Text("Überspringen", color = Color.White) }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                if (index > 0) TextButton(onClick = { index-- }) { Text("Zurück", color = Color.White) }
                Button(onClick = { if (index < steps.lastIndex) index++ else onDone() }) {
                    Text(if (index < steps.lastIndex) "Weiter" else "Los geht's")
                }
            }
        }
    }
}
