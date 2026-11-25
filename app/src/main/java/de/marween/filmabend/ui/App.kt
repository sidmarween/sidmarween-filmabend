@file:OptIn(ExperimentalMaterial3Api::class)

package de.marween.filmabend.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.marween.filmabend.AppViewModel
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object Settings : Screen
    data class Detail(val type: String, val id: Int) : Screen
    data class Person(val name: String, val id: Int) : Screen
}

@Composable
fun FilmabendApp(vm: AppViewModel, tab: Int, setTab: (Int) -> Unit) {
    val uid by vm.uid.collectAsStateWithLifecycle()
    val loaded by vm.profileLoaded.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    when {
        uid == null -> AuthScreen(vm)
        !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { TiwiLoading(null) }
        profile?.hid == null -> HouseholdSetupScreen(vm)
        else -> MainShell(vm, tab, setTab)
    }
}

@Composable
private fun MainShell(vm: AppViewModel, tab: Int, setTab: (Int) -> Unit) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val snack = remember { SnackbarHostState() }
    val msg by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(msg) {
        msg?.let {
            val undo = vm.undo.value
            vm.undo.value = null
            val res = snack.showSnackbar(it, actionLabel = if (undo != null) "Rückgängig" else null, duration = androidx.compose.material3.SnackbarDuration.Short)
            if (res == androidx.compose.material3.SnackbarResult.ActionPerformed) undo?.invoke()
            vm.message.value = null
        }
    }
    val open: (Screen) -> Unit = { stack.add(it) }
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.size > 1) { back() }

    val uid by vm.uid.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val unseen = suggestions.count { it.to == uid && !it.seen }

    Box(Modifier.fillMaxSize()) {
        // Die Startseite bleibt immer im Hintergrund erhalten – so landet man nach „Zurück“
        // genau wieder an der Stelle in der Liste, wo man war.
        run {
            Scaffold(
                snackbarHost = { SnackbarHost(snack) },
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                listOf("Unsere Filme & Serien", "Sammlungen", "Neu & Trailer", "Unsere Charts", "Post")[tab],
                                fontWeight = FontWeight.Bold
                            )
                        },
                        actions = {
                            if (tab == 2) IconButton(onClick = { vm.refreshAll() }, modifier = Modifier.tourTarget("refresh")) { Icon(Icons.Filled.Refresh, "Alles aktualisieren") }
                            IconButton(onClick = { open(Screen.Search) }, modifier = Modifier.tourTarget("search")) { Icon(Icons.Filled.Search, "Suchen") }
                            IconButton(onClick = { open(Screen.Settings) }, modifier = Modifier.tourTarget("settings")) { Icon(Icons.Filled.Settings, "Einstellungen") }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                    )
                },
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(tab == 0, { setTab(0) }, { Icon(Icons.Filled.VideoLibrary, null) }, label = { Text("Bibliothek") }, modifier = Modifier.tourTarget("nav0"))
                        NavigationBarItem(tab == 1, { setTab(1) }, { Icon(Icons.Filled.CollectionsBookmark, null) }, label = { Text("Sammlungen") }, modifier = Modifier.tourTarget("nav1"))
                        NavigationBarItem(tab == 2, { setTab(2) }, { Icon(Icons.Filled.Movie, null) }, label = { Text("Neu") }, modifier = Modifier.tourTarget("nav2"))
                        NavigationBarItem(tab == 3, { setTab(3) }, { Icon(Icons.Filled.Leaderboard, null) }, label = { Text("Charts") }, modifier = Modifier.tourTarget("nav3"))
                        NavigationBarItem(tab == 4, { setTab(4) }, {
                            BadgedBox(badge = { if (unseen > 0) Badge { Text("$unseen") } }) { Icon(Icons.Filled.Mail, null) }
                        }, label = { Text("Post") }, modifier = Modifier.tourTarget("nav4"))
                    }
                }
            ) { pad ->
                Box(Modifier.padding(pad)) {
                    val openDetail: (String, Int) -> Unit = { t, id -> open(Screen.Detail(t, id)) }
                    when (tab) {
                        0 -> LibraryScreen(vm, openDetail) { open(Screen.Search) }
                        1 -> CollectionsScreen(vm, openDetail)
                        2 -> NewScreen(vm, openDetail)
                        3 -> ChartsScreen(vm, openDetail)
                        else -> PostScreen(vm, openDetail)
                    }
                }
            }
        }
        // Weitere Seiten liegen darüber; auch sie behalten ihren Stand, wenn man tiefer geht
        stack.forEachIndexed { index, screen ->
            if (index == 0) return@forEachIndexed
            androidx.compose.runtime.key(index, screen) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .pointerInput(Unit) { detectTapGestures { } }
                ) {
                    when (screen) {
                        Screen.Home -> {}
                        Screen.Search -> SearchScreen(vm, back, { n, id -> open(Screen.Person(n, id)) }) { t, id -> open(Screen.Detail(t, id)) }
                        is Screen.Person -> PersonScreen(vm, screen.name, screen.id, back) { t, id -> open(Screen.Detail(t, id)) }
                        Screen.Settings -> SettingsScreen(vm, back)
                        is Screen.Detail -> DetailScreen(vm, screen.type, screen.id, back)
                    }
                }
            }
        }
        if (stack.last() != Screen.Home) {
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
        // Erklärmodus mit Tiwi (erster Start oder aus den Einstellungen)
        val tour by vm.tourActive.collectAsStateWithLifecycle()
        if (tour) {
            LaunchedEffect(Unit) { while (stack.size > 1) stack.removeAt(stack.lastIndex) }
            TourOverlay(setTab) { vm.endTour() }
        }
    }
}

// ---------------- Anmeldung ----------------

@Composable
private fun AuthScreen(vm: AppViewModel) {
    var register by rememberSaveable { mutableStateOf(true) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var pw by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(40.dp))
        Tiwi(TiwiPose.Waving, Modifier.height(120.dp))
        Text("Filmabend", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Gold)
        Text(
            "Eure gemeinsame Film- & Serienliste.\nAbhaken, bewerten, Vorschläge schicken.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        if (register) {
            OutlinedTextField(
                name, { name = it }, label = { Text("Dein Vorname") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth()
            )
        }
        OutlinedTextField(
            email, { email = it }, label = { Text("E-Mail") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            pw, { pw = it }, label = { Text("Passwort (mind. 6 Zeichen)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth()
        )
        error?.let { Text(it, color = Rose, textAlign = TextAlign.Center) }
        Button(
            enabled = !busy && email.isNotBlank() && pw.length >= 6 && (!register || name.isNotBlank()),
            onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        if (register) vm.register(email, pw, name) else vm.login(email, pw)
                    } catch (e: Exception) {
                        error = germanAuthError(e)
                    }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) { Text(if (register) "Konto erstellen" else "Anmelden") }
        TextButton(onClick = { register = !register; error = null }) {
            Text(if (register) "Ich habe schon ein Konto – anmelden" else "Neu hier? Konto erstellen")
        }
        if (!register) {
            TextButton(enabled = email.isNotBlank(), onClick = {
                scope.launch {
                    error = try { vm.resetPassword(email); "Mail zum Zurücksetzen wurde verschickt." } catch (e: Exception) { germanAuthError(e) }
                }
            }) { Text("Passwort vergessen?") }
        }
        Text("© marween", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun germanAuthError(e: Exception): String {
    val m = e.message ?: ""
    return when {
        "badly formatted" in m -> "Die E-Mail-Adresse sieht nicht richtig aus."
        "already in use" in m -> "Mit dieser E-Mail gibt es schon ein Konto – bitte anmelden."
        "password is invalid" in m || "INVALID_LOGIN_CREDENTIALS" in m || "credential is incorrect" in m ->
            "E-Mail oder Passwort stimmt nicht."
        "no user record" in m -> "Zu dieser E-Mail gibt es kein Konto."
        "network" in m.lowercase() -> "Keine Internetverbindung."
        "CONFIGURATION_NOT_FOUND" in m || "operation is not allowed" in m ->
            "In Firebase ist die Anmeldung per E-Mail noch nicht eingeschaltet (siehe Anleitung)."
        else -> m.ifBlank { "Das hat nicht geklappt." }
    }
}

// ---------------- Haushalt verbinden ----------------

@Composable
private fun HouseholdSetupScreen(vm: AppViewModel) {
    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val profile by vm.profile.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(40.dp))
        Tiwi(TiwiPose.Laughing, Modifier.height(104.dp))
        Text("Hallo ${profile?.name ?: ""}!", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Verbindet eure beiden Handys: Einer von euch legt eure gemeinsame Liste an und bekommt einen Code. " +
                "Der andere gibt diesen Code hier ein.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            enabled = !busy,
            onClick = {
                busy = true; error = null
                scope.launch {
                    try { vm.createHousehold() } catch (e: Exception) { error = e.localizedMessage }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) { Text("Gemeinsame Liste anlegen") }
        Text("– oder –", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            code, { code = it.uppercase().take(6) }, label = { Text("Code vom Partner") }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(
            enabled = !busy && code.length == 6,
            onClick = {
                busy = true; error = null
                scope.launch {
                    try { vm.joinHousehold(code) } catch (e: Exception) {
                        error = if ("PERMISSION_DENIED" in (e.message ?: "")) "Der Code passt nicht oder die Liste ist schon voll." else e.localizedMessage
                    }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) { Text("Mit Code verbinden") }
        error?.let { Text(it, color = Rose, textAlign = TextAlign.Center) }
        TextButton(onClick = { vm.logout() }) { Text("Abmelden") }
    }
}
