package de.marween.filmabend.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.marween.filmabend.AppViewModel
import de.marween.filmabend.TitleInfo
import de.marween.filmabend.normName

/**
 * Die zwei Knöpfe für Paare – überall unter „Neu“ gleich:
 *  💑 „Zusammen gucken“ (eure gemeinsame Liste) und ➤ „An … schicken“.
 *
 * [title] dient nur zum Anzeigen/Wiederfinden; [withInfo] liefert den echten Titel
 * (bei manchen Quellen muss er erst in der Filmdatenbank nachgeschlagen werden).
 */
@Composable
fun PartnerButtons(
    vm: AppViewModel,
    title: String,
    key: String?,
    withInfo: ((TitleInfo) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uid by vm.uid.collectAsStateWithLifecycle()
    val hh by vm.household.collectAsStateWithLifecycle()
    val titles by vm.titles.collectAsStateWithLifecycle()
    val partner = hh?.partnerOf(uid)
    val pName = partner?.let { hh?.nameOf(it) } ?: "Partner"
    val t = titles.firstOrNull { key != null && it.key == key }
        ?: titles.firstOrNull { normName(it.title) == normName(title) }
    val together = t?.together == true
    var dialog by remember { mutableStateOf(false) }

    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(
            onClick = { if (t != null) vm.setTogether(t.info, !together) else withInfo { vm.setTogether(it, true) } },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            colors = if (together) ButtonDefaults.filledTonalButtonColors(containerColor = Rose, contentColor = Color.White)
            else ButtonDefaults.filledTonalButtonColors(),
            modifier = Modifier.weight(1f)
        ) {
            Icon(if (together) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null)
            Text(if (together) " Zusammen ✓" else " Zusammen gucken", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (partner != null) {
            OutlinedButton(
                onClick = { dialog = true },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, null)
                Text(" An $pName", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    if (dialog) {
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { dialog = false },
            title = { Text("An $pName schicken") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("„$title“")
                    OutlinedTextField(note, { note = it }, label = { Text("Nachricht (optional)") }, placeholder = { Text("Wollen wir den zusammen gucken?") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = note
                    dialog = false
                    withInfo { vm.suggest(it, n) }
                }) { Text("Senden") }
            },
            dismissButton = { TextButton(onClick = { dialog = false }) { Text("Abbrechen") } }
        )
    }
}
