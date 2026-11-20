@file:OptIn(ExperimentalMaterial3Api::class)

package de.marween.filmabend.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.marween.filmabend.AppViewModel
import de.marween.filmabend.BuildConfig

/** Änderungsliste – bei jeder neuen Version oben ergänzen. */
private val changelog = listOf(
    "1.1" to listOf(
        "Behoben: Gesehen-Haken und Bewertungen reagierten direkt nach dem Anlegen der Liste nicht",
        "Englische Beschreibungen werden automatisch ins Deutsche übersetzt",
        "Neu: Seite „Neu & Trailer“ mit neuen Filmen (deutsche Trailer) und Serienstarts",
        "Trailer-Knopf auf jeder Film- und Serienseite",
        "Trailer: neueste deutsche Trailer von Netflix, Prime Video, Disney+, Paramount+, Sky, RTL+ & Kino",
        "DVD & Blu-ray: Neuerscheinungen der letzten 8 Wochen – jetzt mit Cover",
        "Sofort-Benachrichtigung bei neuen Vorschlägen",
        "Fehlende Bilder werden automatisch gesucht (JustWatch, TVmaze, Mediatheken, Wikipedia)",
        "WhatsApp-Liste: eure bisher gesehenen Filme & Serien auf einen Tipp übernehmen",
        "Filmdaten jetzt von JustWatch: deutsche Titel, Poster, Trailer und „Läuft bei“",
        "Entdecken: nach Genre stöbern (Comedy, Thriller, Action …) und schnell bewerten",
        "📺 TV-Sendungen privater & öffentlich-rechtlicher Sender, sortiert nach eurem Geschmack",
        "🎭 Schauspieler-Suche: alle Filme & Serien einer Person",
        "Behoben: „nicht gefunden“ beim Senden/Merken – klappt jetzt mit jedem Titel",
        "„Alles aktualisieren“-Knopf unter „Neu“ holt die neuesten Trailer und Neuheiten",
        "💑 Zusammen gucken: eure gemeinsame Liste – aus jedem Bereich unter „Neu“ mit einem Tipp",
        "„An …“-Knopf überall: Titel mit Nachricht an den Partner schicken",
        "Mediatheken: neue Filme & Serien von ARD, ZDF, arte, 3sat, WDR, SWR, NDR, BR … direkt ansehen",
    ),
    "1.0" to listOf(
        "Erste Version: gemeinsame Bibliothek für Filme & Serien",
        "Abhaken, Merkliste, Staffeln pro Person",
        "Bewertung 1–10 und Charts (Top, Flop, uneinig, Statistik)",
        "Vorschläge nach eurem Geschmack",
        "Filmdaten ohne Schlüssel: Apple iTunes (Filme) und TVmaze (Serien)",
        "Gegenseitig Titel vorschlagen inkl. Benachrichtigung",
    ),
)

@Composable
fun SettingsScreen(vm: AppViewModel, back: () -> Unit) {
    val uid by vm.uid.collectAsStateWithLifecycle()
    val hh by vm.household.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var name by remember(profile?.name) { mutableStateOf(profile?.name ?: "") }
    val partner = hh?.partnerOf(uid)

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Einstellungen") },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } }
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionCard {
                Text("Dein Name", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(enabled = name.isNotBlank() && name != profile?.name, onClick = { vm.saveName(name) }) { Text("Speichern") }
                }
            }
            SectionCard {
                Text("Eure gemeinsame Liste", style = MaterialTheme.typography.titleMedium)
                if (partner != null) {
                    Text("Verbunden mit ${hh?.nameOf(partner)} 💑", color = Gold)
                } else {
                    Text("Gib deinem Partner diesen Code. Er/sie erstellt in der App ein Konto und tippt „Mit Code verbinden“.")
                    Text(hh?.code ?: "…", fontFamily = FontFamily.Monospace, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Gold,
                        modifier = Modifier.padding(vertical = 8.dp))
                    Button(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(
                            Intent.EXTRA_TEXT,
                            "Lass uns Filmabend zusammen nutzen 🍿 Mein Code: ${hh?.code}"
                        )
                        ctx.startActivity(Intent.createChooser(send, "Code teilen"))
                    }) {
                        Icon(Icons.Filled.Share, null); Text("  Code teilen")
                    }
                }
            }
            SectionCard {
                Text("WhatsApp-Liste übernehmen", style = MaterialTheme.typography.titleMedium)
                val n = remember { runCatching { org.json.JSONArray(ctx.assets.open("whatsapp_gesehen.json").bufferedReader().use { it.readText() }).length() }.getOrDefault(0) }
                var busy by remember { mutableStateOf(false) }
                var done by remember { mutableStateOf(vm.waImported()) }
                Text(
                    "$n Filme & Serien aus eurem WhatsApp-Chat „Filme & Serien“ – werden für euch beide als gesehen abgehakt. " +
                        "Danach könnt ihr sie in der Bibliothek bewerten oder einzelne wieder abhaken.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(enabled = !busy && n > 0, onClick = {
                    busy = true
                    vm.importWhatsApp { busy = false; done = true }
                }) { Text(if (busy) "Wird übernommen …" else if (done) "Nochmal übernehmen" else "Jetzt übernehmen") }
                if (done) Text("✓ Schon übernommen", color = Gold, style = MaterialTheme.typography.labelMedium)
            }
            SectionCard {
                Text("Benachrichtigungen", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Wenn dein Partner dir etwas vorschlägt, kommt sofort eine Benachrichtigung – solange die App im Hintergrund läuft. " +
                        "Ist sie ganz geschlossen, prüft sie alle ~15 Minuten. Damit Android das nicht blockiert, nimm die App aus der Akku-Optimierung heraus.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        de.marween.filmabend.SuggestionWorker.notify(ctx, "So sieht ein Vorschlag aus 🍿", "„Testfilm“ – Das musst du sehen!")
                    }) { Text("Test") }
                    OutlinedButton(onClick = {
                        runCatching {
                            ctx.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }) { Text("Akku-Einstellungen") }
                }
            }
            SectionCard {
                Text("Info", style = MaterialTheme.typography.titleMedium)
                Text("Filmabend ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Filmdaten: Apple iTunes Store (Deutschland). Seriendaten: TVmaze.com (CC BY-SA). Mediatheken: MediathekViewWeb.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                changelog.forEach { (v, items) ->
                    Text("Version $v", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    items.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
                Text("© marween", modifier = Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { vm.logout() }, modifier = Modifier.fillMaxWidth()) { Text("Abmelden") }
        }
    }
}
