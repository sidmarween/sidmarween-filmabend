package de.marween.filmabend

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

enum class RecMode { BOTH, ME, PARTNER }

data class Rec(val item: CatalogItem, val score: Double, val reason: String)

data class TvRec(val show: TvShow, val score: Double, val reason: String)

data class TvState(
    val loading: Boolean = false,
    val items: List<TvRec> = emptyList(),
    val error: String? = null,
)

data class DiscState(
    val loading: Boolean = false,
    val items: List<DiscRelease> = emptyList(),
    val error: String? = null,
)

data class TrState(
    val loading: Boolean = false,
    val items: List<TrailerItem> = emptyList(),
    val error: String? = null,
)

data class MtState(
    val loading: Boolean = false,
    val items: List<MediathekItem> = emptyList(),
    val error: String? = null,
)

data class RecState(
    val loading: Boolean = false,
    val items: List<Rec> = emptyList(),
    val error: String? = null,
    val fallback: Boolean = false,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val prefs = app.getSharedPreferences("filmabend", Context.MODE_PRIVATE)

    val uid = MutableStateFlow(auth.currentUser?.uid)

    /** Erklärmodus mit Tiwi – läuft beim ersten Start automatisch. */
    val tourActive = MutableStateFlow(!prefs.getBoolean("tourDone", false))
    fun startTour() { tourActive.value = true }
    fun endTour() { prefs.edit().putBoolean("tourDone", true).apply(); tourActive.value = false }
    val profile = MutableStateFlow<Profile?>(null)
    val profileLoaded = MutableStateFlow(false)
    val household = MutableStateFlow<Household?>(null)
    val titles = MutableStateFlow<List<Title>>(emptyList())
    val suggestions = MutableStateFlow<List<Suggestion>>(emptyList())
    val recs = MutableStateFlow(RecState())
    val genreList = MutableStateFlow(RecState())
    private var genreLoaded: String? = null
    val message = MutableStateFlow<String?>(null)
    val newMovies = MutableStateFlow(RecState())
    val newShows = MutableStateFlow(RecState())
    val mtFilms = MutableStateFlow(MtState())
    val trailers = MutableStateFlow(TrState())
    val discs = MutableStateFlow(DiscState())
    val tv = MutableStateFlow(TvState())
    /** Tipp des Tages: was heute laut Programmführer (EPG) im Fernsehen läuft */
    val tvToday = MutableStateFlow(TvState())
    val mtSeries = MutableStateFlow(MtState())

    private var userReg: ListenerRegistration? = null
    private val hhRegs = mutableListOf<ListenerRegistration>()
    private var currentHid: String? = null

    init {
        auth.addAuthStateListener { a ->
            val newUid = a.currentUser?.uid
            if (newUid != uid.value || userReg == null) {
                uid.value = newUid
                attachUser(newUid)
            }
        }
    }

    private fun attachUser(u: String?) {
        userReg?.remove(); userReg = null
        detachHousehold()
        profile.value = null
        profileLoaded.value = false
        if (u == null) {
            prefs.edit().remove("uid").remove("hid").apply()
            profileLoaded.value = true
            return
        }
        prefs.edit().putString("uid", u).apply()
        userReg = db.collection("users").document(u).addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
            if (err != null) { message.value = "Profil konnte nicht geladen werden: ${err.message}"; profileLoaded.value = true; return@addSnapshotListener }
            val p = if (snap != null && snap.exists()) Profile(snap.getString("name") ?: "", snap.getString("hid")) else null
            profile.value = p
            profileLoaded.value = true
            // Erst verbinden, wenn der Server die Liste wirklich angelegt hat –
            // sonst lehnt Firestore das Mitlesen ab und die Knöpfe wirken „tot“.
            if (snap?.metadata?.hasPendingWrites() == true) return@addSnapshotListener
            if (p?.hid != currentHid) attachHousehold(p?.hid)
        }
    }

    private fun detachHousehold() {
        hhRegs.forEach { it.remove() }; hhRegs.clear()
        currentHid = null
        household.value = null; titles.value = emptyList(); suggestions.value = emptyList()
        recs.value = RecState()
    }

    private fun attachHousehold(hid: String?) {
        detachHousehold()
        currentHid = hid
        if (hid == null) { prefs.edit().remove("hid").apply(); return }
        prefs.edit().putString("hid", hid).apply()
        val ref = db.collection("households").document(hid)
        hhRegs += ref.addSnapshotListener { s, e ->
            if (e != null) { retryHousehold(hid, e); return@addSnapshotListener }
            if (s != null && s.exists()) {
                household.value = Household(
                    id = s.id,
                    code = s.getString("code") ?: "",
                    members = (s.get("members") as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                    names = (s.get("names") as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v.toString() } ?: emptyMap(),
                )
            }
        }
        hhRegs += ref.collection("titles").addSnapshotListener { q, e ->
            if (e != null) { retryHousehold(hid, e); return@addSnapshotListener }
            if (q != null) {
                retries = 0
                titles.value = q.documents.mapNotNull { Title.from(it) }
                val noPic = q.documents.filter { (it.getString("poster") ?: "").isBlank() && it.getBoolean("posterChecked") != true }
                if (noPic.isNotEmpty()) fillMissingPosters()
            }
        }
        hhRegs += ref.collection("suggestions").addSnapshotListener { q, e ->
            if (e != null) { retryHousehold(hid, e); return@addSnapshotListener }
            if (q != null) {
                suggestions.value = q.documents.mapNotNull { Suggestion.from(it) }.sortedByDescending { it.createdAt }
                // Sofort-Benachrichtigung, solange die App läuft (auch im Hintergrund)
                val me = uid.value
                val fresh = q.documents.filter { it.getString("to") == me && it.getBoolean("notified") == false && !it.metadata.hasPendingWrites() }
                if (fresh.isNotEmpty()) {
                    val list = fresh.mapNotNull { Suggestion.from(it) }
                    val from = household.value?.nameOf(list.firstOrNull()?.from) ?: "Dein Schatz"
                    val text = if (list.size == 1) "„${list[0].title}“" + (if (list[0].note.isNotBlank()) " – ${list[0].note}" else "")
                    else list.joinToString(", ") { it.title }
                    SuggestionWorker.notify(
                        getApplication(),
                        if (list.size == 1) "$from hat dir was vorgeschlagen 🍿" else "$from hat dir ${list.size} Vorschläge geschickt 🍿",
                        text
                    )
                    db.runBatch { b -> fresh.forEach { b.update(it.reference, "notified", true) } }
                }
            }
        }
    }

    private var retries = 0
    private var retryPending = false

    /** Mitlesen ist abgebrochen (z. B. Liste gerade erst angelegt) – kurz warten und neu verbinden. */
    private fun retryHousehold(hid: String, e: Exception) {
        if (retryPending) return
        retryPending = true
        retries++
        if (retries == 4) message.value = "Verbindung zur gemeinsamen Liste klappt nicht: ${e.localizedMessage}"
        viewModelScope.launch {
            kotlinx.coroutines.delay(if (retries < 4) 2000L else 15000L)
            retryPending = false
            if (currentHid == hid) attachHousehold(hid)
        }
    }

    private fun hhRef() = currentHid?.let { db.collection("households").document(it) }

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() } catch (e: Exception) { message.value = e.localizedMessage ?: "Unbekannter Fehler" }
        }
    }

    // ---------- Anmeldung ----------

    suspend fun register(email: String, pw: String, name: String) {
        val res = auth.createUserWithEmailAndPassword(email.trim(), pw).await()
        val u = res.user ?: return
        db.collection("users").document(u.uid).set(mapOf("name" to name.trim()), SetOptions.merge()).await()
    }

    suspend fun login(email: String, pw: String) {
        auth.signInWithEmailAndPassword(email.trim(), pw).await()
    }

    suspend fun resetPassword(email: String) {
        auth.sendPasswordResetEmail(email.trim()).await()
    }

    fun logout() = auth.signOut()

    fun saveName(name: String) = run {
        val u = uid.value ?: return@run
        db.collection("users").document(u).set(mapOf("name" to name.trim()), SetOptions.merge()).await()
        hhRef()?.update("names.$u", name.trim())?.await()
    }

    // ---------- Gemeinsamer Haushalt ----------

    suspend fun createHousehold() {
        val u = uid.value ?: return
        val name = profile.value?.name ?: ""
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val code = (1..6).map { chars.random() }.joinToString("")
        val hh = db.collection("households").document()
        db.runBatch { b ->
            b.set(hh, mapOf(
                "members" to listOf(u),
                "names" to mapOf(u to name),
                "code" to code,
                "createdAt" to System.currentTimeMillis(),
            ))
            b.set(db.collection("codes").document(code), mapOf("hid" to hh.id))
            b.set(db.collection("users").document(u), mapOf("hid" to hh.id), SetOptions.merge())
        }.await()
    }

    suspend fun joinHousehold(codeInput: String) {
        val u = uid.value ?: return
        val code = codeInput.trim().uppercase()
        val c = db.collection("codes").document(code).get().await()
        val hid = c.getString("hid") ?: throw IllegalStateException("Diesen Code gibt es nicht.")
        db.collection("households").document(hid).update(
            mapOf(
                "members" to FieldValue.arrayUnion(u),
                "names.$u" to (profile.value?.name ?: ""),
            )
        ).await()
        db.collection("users").document(u).set(mapOf("hid" to hid), SetOptions.merge()).await()
    }

    // ---------- Bibliothek ----------

    private fun meta(i: TitleInfo, existing: Title?): MutableMap<String, Any?> {
        val m = mutableMapOf<String, Any?>(
            "mediaId" to i.mediaId,
            "type" to i.type,
            "title" to i.title,
            "year" to i.year,
            "poster" to (i.poster ?: ""),
            "overview" to i.overview,
        )
        if (i.genres.isNotEmpty()) m["genres"] = i.genres
        if (i.seasons > 0) m["seasons"] = i.seasons
        if (existing == null) m["addedAt"] = System.currentTimeMillis()
        return m
    }

    fun titleFor(key: String): Title? = titles.value.firstOrNull { it.key == key }

    private fun write(i: TitleInfo, changes: Map<String, Any?>) = run {
        val ref = hhRef() ?: return@run
        val data = meta(i, titleFor(i.key))
        data.putAll(changes)
        ref.collection("titles").document(i.key).set(data, SetOptions.merge()).await()
    }

    fun setWatched(i: TitleInfo, value: Boolean) {
        val u = uid.value ?: return
        val ch = mutableMapOf<String, Any?>(
            "watched" to mapOf(u to value),
            "watchedAt" to mapOf(u to if (value) System.currentTimeMillis() else FieldValue.delete()),
        )
        if (value) ch["watchlist"] = mapOf(u to FieldValue.delete())
        write(i, ch)
    }

    fun setWatchlist(i: TitleInfo, value: Boolean) {
        val u = uid.value ?: return
        write(i, mapOf("watchlist" to mapOf(u to if (value) true else FieldValue.delete())))
    }

    /** Gemeinsame Liste „Zusammen gucken“. */
    fun setTogether(i: TitleInfo, value: Boolean) {
        val u = uid.value ?: return
        write(i, mapOf("together" to value, "togetherBy" to if (value) u else FieldValue.delete(), "togetherAt" to System.currentTimeMillis()))
        if (value) message.value = "„${i.title}“ steht jetzt auf eurer Zusammen-Liste 💑"
    }

    /** Für den Snackbar-Knopf „Rückgängig“ */
    val undo = MutableStateFlow<(() -> Unit)?>(null)

    /**
     * „Nicht mein Fall“: Titel wird für mich nicht mehr vorgeschlagen und zählt
     * im Geschmacksprofil wie eine schlechte Wertung (2/10).
     */
    fun setRejected(i: TitleInfo, value: Boolean) {
        val u = uid.value ?: return
        val ch = mutableMapOf<String, Any?>("rejected" to mapOf(u to if (value) true else FieldValue.delete()))
        if (value) ch["watchlist"] = mapOf(u to FieldValue.delete())
        write(i, ch)
        if (value) {
            undo.value = { setRejected(i, false) }
            message.value = "„${i.title}“ abgelehnt – wird nicht mehr vorgeschlagen"
        }
    }

    fun setRating(i: TitleInfo, rating: Int?) {
        val u = uid.value ?: return
        val ch = mutableMapOf<String, Any?>("ratings" to mapOf(u to (rating ?: FieldValue.delete())))
        val t = titleFor(i.key)
        if (rating != null && !t.isWatched(u)) {
            ch["watched"] = mapOf(u to true)
            ch["watchedAt"] = mapOf(u to System.currentTimeMillis())
            ch["watchlist"] = mapOf(u to FieldValue.delete())
        }
        write(i, ch)
    }

    fun setSeason(i: TitleInfo, season: Int, value: Boolean) {
        val u = uid.value ?: return
        val t = titleFor(i.key)
        val now = (t?.seasonsOf(u) ?: emptyList()).toMutableSet()
        if (value) now += season else now -= season
        val ch = mutableMapOf<String, Any?>("seasonsWatched" to mapOf(u to now.sorted()))
        // Alle Staffeln gesehen -> Serie automatisch als gesehen markieren
        if (value && i.seasons > 0 && now.size >= i.seasons && !t.isWatched(u)) {
            ch["watched"] = mapOf(u to true)
            ch["watchedAt"] = mapOf(u to System.currentTimeMillis())
            ch["watchlist"] = mapOf(u to FieldValue.delete())
        }
        write(i, ch)
    }

    private fun Title?.isWatched(u: String) = this?.isWatched(u) == true

    // ---------- Vorschläge an den Partner ----------

    fun suggest(i: TitleInfo, note: String, onDone: () -> Unit = {}) = run {
        val ref = hhRef() ?: return@run
        val u = uid.value ?: return@run
        val to = household.value?.partnerOf(u) ?: throw IllegalStateException("Dein Partner ist noch nicht verbunden.")
        // Titel auch in eurer Bibliothek anlegen, damit die Detailseite immer funktioniert
        ref.collection("titles").document(i.key).set(meta(i, titleFor(i.key)), SetOptions.merge()).await()
        ref.collection("suggestions").add(mapOf(
            "from" to u, "to" to to, "key" to i.key, "mediaId" to i.mediaId, "type" to i.type,
            "title" to i.title, "year" to i.year, "poster" to (i.poster ?: ""), "note" to note.trim(),
            "createdAt" to System.currentTimeMillis(), "status" to "offen", "seen" to false, "notified" to false,
        )).await()
        message.value = "Vorschlag an ${household.value?.nameOf(to)} geschickt"
        onDone()
    }

    fun answerSuggestion(s: Suggestion, accept: Boolean) {
        val ref = hhRef() ?: return
        run {
            ref.collection("suggestions").document(s.id)
                .update(mapOf("status" to if (accept) "angenommen" else "abgelehnt", "seen" to true, "notified" to true)).await()
        }
        if (accept) setTogether(TitleInfo(s.mediaId, s.type, s.title, s.year, s.poster, ""), true)
    }

    fun deleteSuggestion(s: Suggestion) = run {
        hhRef()?.collection("suggestions")?.document(s.id)?.delete()?.await()
    }

    fun markSuggestionsSeen() = run {
        val u = uid.value ?: return@run
        val ref = hhRef() ?: return@run
        val unseen = suggestions.value.filter { it.to == u && !it.seen }
        if (unseen.isEmpty()) return@run
        db.runBatch { b ->
            unseen.forEach { b.update(ref.collection("suggestions").document(it.id), mapOf("seen" to true, "notified" to true)) }
        }.await()
    }

    // ---------- Empfehlungen nach eurem Geschmack ----------

    /** Für Serien aus den Charts erst die echte Serie nachschlagen, dann [action] ausführen. */
    fun withResolved(item: CatalogItem, action: (CatalogItem) -> Unit) = run {
        val r = runCatching { Catalog.resolve(item) }.getOrNull()
            ?: freeItem(item.title, item.type, item.poster, item.overview)
        action(r)
    }

    /**
     * Wenn ein Titel in keiner Filmdatenbank steht (z. B. deutsche TV-Produktionen),
     * legen wir ihn trotzdem an – mit eigener Nummer. So klappt Senden & Merken immer.
     */
    private fun freeItem(title: String, type: String = "movie", poster: String? = null, overview: String = "") =
        CatalogItem(
            id = -((("mt:" + normName(title)).hashCode() and 0x3fffffff) + 1),
            type = type, title = title.trim(), year = "", poster = poster, overview = overview, vote = 0.0,
        )

    private fun bestMatch(hits: List<CatalogItem>, title: String): CatalogItem? {
        val n = normName(title)
        if (n.isBlank()) return null
        return hits.firstOrNull { normName(it.title) == n }
            ?: hits.firstOrNull { val h = normName(it.title); n.length >= 5 && h.length >= 5 && (h.startsWith(n) || n.startsWith(h)) }
    }

    fun loadRecs(mode: RecMode) {
        val u = uid.value ?: return
        val p = household.value?.partnerOf(u)
        val pName = household.value?.nameOf(p) ?: "Partner"
        val people = when (mode) {
            RecMode.ME -> listOf(u)
            RecMode.PARTNER -> listOfNotNull(p)
            RecMode.BOTH -> listOfNotNull(u, p)
        }
        recs.value = recs.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val lib = titles.value
                // Geschmack pro Genre: gute Wertungen zählen positiv, schlechte negativ
                val taste = HashMap<String, Double>()
                val example = HashMap<String, Pair<String, Int>>()
                for (t in lib) for (who in people) {
                    val r = t.rating(who) ?: (if (t.isRejected(who)) 2 else continue)
                    for (g in t.genres.map { Catalog.genre(it) }.distinct()) {
                        taste[g] = (taste[g] ?: 0.0) + (r - 5.5)
                        if (r >= 7 && (example[g]?.second ?: 0) < r) example[g] = t.title to r
                    }
                }
                val liked = taste.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(4)

                val seenKeys = lib.filter { t -> people.any { t.isWatched(it) || (t.rating(it) ?: 10) <= 3 } }
                    .map { it.key }.toSet()
                val seenShowNames = lib.filter { t -> t.type == "tv" && people.any { t.isWatched(it) } }
                    .map { normName(it.title) }.toSet()
                fun excluded(i: CatalogItem) =
                    i.key in seenKeys || (i.type == "tv" && normName(i.title) in seenShowNames)

                if (liked.isEmpty()) {
                    val movieJob = async { runCatching { JW.popular("movie", first = 40) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: runCatching { Catalog.charts("movie", null, 40) }.getOrDefault(emptyList()) }
                    val tvJob = async { runCatching { JW.popular("tv", first = 40) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: runCatching { Catalog.charts("tv", null, 40) }.getOrDefault(emptyList()) }
                    val lists: List<List<CatalogItem>> = listOf(movieJob.await(), tvJob.await())
                    val mixed = ArrayList<CatalogItem>()
                    for (i in 0 until lists.maxOf { it.size }) lists.forEach { l -> if (i < l.size) mixed += l[i] }
                    val items = mixed.filterNot { excluded(it) }.distinctBy { it.key }
                    if (items.isEmpty()) throw IllegalStateException("Filmdaten gerade nicht erreichbar. Bitte später nochmal.")
                    recs.value = RecState(items = items.map { Rec(it, 0.0, "Gerade beliebt in Deutschland") }, fallback = true)
                    return@launch
                }

                val jobs = ArrayList<Deferred<Triple<String, Double, List<CatalogItem>>>>()
                for (e in liked) for (type in listOf("movie", "tv")) {
                    val g: String = e.key
                    val w: Double = e.value
                    jobs += async {
                        val list: List<CatalogItem> = runCatching { JW.byGenre(g, type, 40) }.getOrNull()?.takeIf { it.isNotEmpty() }
                            ?: runCatching { Catalog.charts(type, g, 40) }.getOrDefault(emptyList())
                        Triple(g, w, list)
                    }
                }
                val results: List<Triple<String, Double, List<CatalogItem>>> = jobs.awaitAll()

                val score = HashMap<String, Double>()
                val items = HashMap<String, CatalogItem>()
                val why = HashMap<String, String>()
                for ((g, w, list) in results) {
                    list.forEachIndexed { idx, item ->
                        if (excluded(item)) return@forEachIndexed
                        val add = w * (1.0 - idx * 0.015).coerceAtLeast(0.3)
                        score[item.key] = (score[item.key] ?: 0.0) + add
                        items[item.key] = item
                        if (why[item.key] == null) why[item.key] = g
                    }
                }
                if (items.isEmpty()) throw IllegalStateException("Filmdaten gerade nicht erreichbar. Bitte später nochmal.")
                val ranked = score.entries.map { (k, sc) ->
                    val g = why[k] ?: ""
                    val ex = example[g]?.first?.let { " – wie „$it“" } ?: ""
                    val reason = when (mode) {
                        RecMode.BOTH -> "Euer Genre: $g$ex"
                        RecMode.ME -> "Du magst $g$ex"
                        RecMode.PARTNER -> "$pName mag $g$ex"
                    }
                    Rec(items.getValue(k), sc, reason)
                }.sortedByDescending { it.score }.take(80)
                recs.value = RecState(items = ranked)
            } catch (e: Exception) {
                recs.value = RecState(error = e.localizedMessage ?: "Vorschläge konnten nicht geladen werden")
            }
        }
    }

    // ---------- Neu & Trailer ----------

    fun loadNew(type: String, force: Boolean = false) {
        val flow = if (type == "tv") newShows else newMovies
        if (!force && (flow.value.loading || flow.value.items.isNotEmpty())) return
        flow.value = flow.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val list = if (type == "tv") Catalog.newShows() else Catalog.newMovies()
                if (list.isEmpty()) throw IllegalStateException("Gerade keine Neuheiten gefunden – später nochmal probieren.")
                flow.value = RecState(items = list.map { Rec(it, 0.0, it.extra) })
            } catch (e: Exception) {
                flow.value = RecState(error = e.localizedMessage ?: "Neuheiten konnten nicht geladen werden")
            }
        }
    }

    fun loadMediathek(series: Boolean, force: Boolean = false) {
        val flow = if (series) mtSeries else mtFilms
        if (!force && (flow.value.loading || flow.value.items.isNotEmpty())) return
        flow.value = flow.value.copy(loading = true, error = null)
        viewModelScope.launch {
            flow.value = try {
                MtState(items = if (series) Mediathek.series() else Mediathek.films())
            } catch (e: Exception) {
                MtState(error = e.localizedMessage ?: "Mediatheken konnten nicht geladen werden")
            }
        }
    }

    fun loadTrailers(force: Boolean = false) {
        if (!force && (trailers.value.loading || trailers.value.items.isNotEmpty())) return
        trailers.value = trailers.value.copy(loading = true, error = null)
        viewModelScope.launch {
            trailers.value = try {
                TrState(items = Trailers.latest())
            } catch (e: Exception) {
                TrState(error = e.localizedMessage ?: "Trailer konnten nicht geladen werden")
            }
        }
    }

    /** Zu einem Trailer den passenden Film bzw. die Serie suchen und damit [action] ausführen. */
    fun withTrailerTitle(tr: TrailerItem, action: (CatalogItem) -> Unit) = run {
        val hits = runCatching { Catalog.search(tr.show) }.getOrDefault(emptyList())
        val best = bestMatch(hits, tr.show)?.let { runCatching { Catalog.resolve(it) }.getOrNull() }
        val looksLikeSeries = Regex("staffel|season|serie|\\bS\\d+", RegexOption.IGNORE_CASE).containsMatchIn(tr.title)
        action(best ?: freeItem(tr.show, if (looksLikeSeries) "tv" else "movie", tr.thumb, "Trailer: ${tr.url}"))
    }

    fun loadDiscs(force: Boolean = false) {
        if (!force && (discs.value.loading || discs.value.items.isNotEmpty())) return
        discs.value = discs.value.copy(loading = true, error = null)
        viewModelScope.launch {
            discs.value = try {
                DiscState(items = DiscReleases.latest(8))
            } catch (e: Exception) {
                DiscState(error = e.localizedMessage ?: "DVD-Neuheiten konnten nicht geladen werden")
            }
        }
    }

    /** Titel (z. B. von einer DVD-Liste) in der Filmdatenbank suchen und damit [action] ausführen. */
    fun withTitle(title: String, action: (CatalogItem) -> Unit) = run {
        val hit = TitleLookup.find(title)?.let { runCatching { Catalog.resolve(it) }.getOrNull() }
        action(hit ?: freeItem(title))
    }

    /** Alle Seiten unter „Neu“ frisch laden – neueste Trailer, Neuheiten, Mediatheken, DVDs. */
    fun refreshAll() {
        message.value = "Alles wird aktualisiert …"
        loadTrailers(force = true)
        loadNew("movie", force = true)
        loadNew("tv", force = true)
        loadDiscs(force = true)
        loadTv(force = true)
        loadTvToday(force = true)
        loadMediathek(series = false, force = true)
        loadMediathek(series = true, force = true)
    }

    /** Euer Geschmack pro Genre, aus allen Bewertungen von euch beiden (gut = plus, schlecht = minus). */
    private fun tasteOfBoth(): Pair<Map<String, Double>, Map<String, String>> {
        val u = uid.value
        val people = listOfNotNull(u, household.value?.partnerOf(u))
        val taste = HashMap<String, Double>()
        val example = HashMap<String, Pair<String, Int>>()
        for (t in titles.value) for (who in people) {
            val r = t.rating(who) ?: (if (t.isRejected(who)) 2 else continue)
            for (g in t.genres.map { Catalog.genre(it) }.distinct()) {
                taste[g] = (taste[g] ?: 0.0) + (r - 5.5)
                if (r >= 7 && (example[g]?.second ?: 0) < r) example[g] = t.title to r
            }
        }
        return taste to example.mapValues { it.value.first }
    }

    /** TV-Sendungen der nächsten 7 Tage, sortiert nach eurem Geschmack. */
    fun loadTv(force: Boolean = false) {
        if (!force && (tv.value.loading || tv.value.items.isNotEmpty())) return
        tv.value = tv.value.copy(loading = true, error = null)
        viewModelScope.launch {
            tv.value = try {
                val shows = GermanTv.upcoming(10)
                val (taste, example) = tasteOfBoth()
                val u = uid.value
                val p = household.value?.partnerOf(u)
                val lib = titles.value.associateBy { normName(it.title) }
                val maxTaste = taste.values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
                val ranked = shows.map { s ->
                    val known = lib[normName(s.item.title)]
                    val best = listOfNotNull(known?.rating(u), known?.rating(p)).maxOrNull()
                    val genreScore = s.item.genres.sumOf { (taste[it] ?: 0.0) / maxTaste }
                    val topGenre = s.item.genres.filter { (taste[it] ?: 0.0) > 0 }.maxByOrNull { taste[it] ?: 0.0 }
                    var score = genreScore * 3 + s.item.weight / 40.0 + (if (s.nextAir > 0) 1.0 else 0.0)
                    val reason = when {
                        best != null && best >= 7 -> { score += 10; "Habt ihr mit $best/10 bewertet – läuft wieder" }
                        best != null && best <= 4 -> { score -= 10; "Fandet ihr nicht so gut" }
                        known?.isRejected(p) == true -> { score -= 8; "Nicht ganz euer Fall" }
                        topGenre != null -> "Ihr mögt $topGenre" + (example[topGenre]?.let { " – wie „$it“" } ?: "")
                        else -> "Beliebt"
                    }
                    // Etwas tägliche Würze, damit nicht jeden Tag dieselben oben stehen
                    score += dayJitter(s.item.key) * 2.5
                    TvRec(s, score, reason)
                }.sortedByDescending { it.score }
                TvState(items = diversify(ranked) { it.show.kind })
            } catch (e: Exception) {
                TvState(error = e.localizedMessage ?: "Fernsehprogramm konnte nicht geladen werden")
            }
        }
    }

    /**
     * Tipp des Tages aus dem echten Fernsehprogramm (EPG) aller großen Sender:
     * nur was heute (bis ca. 2 Uhr nachts) läuft, nach eurem Geschmack sortiert.
     */
    fun loadTvToday(force: Boolean = false) {
        if (!force && (tvToday.value.loading || tvToday.value.items.isNotEmpty())) return
        tvToday.value = tvToday.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val all = Epg.load(getApplication<Application>().cacheDir, force).filter { Epg.worthIt(it) }
                val zone = java.time.ZoneId.of("Europe/Berlin")
                val nowSec = System.currentTimeMillis() / 1000
                val tonightEnd = java.time.LocalDate.now(zone).plusDays(1).atTime(2, 0).atZone(zone).toEpochSecond()
                var window = all.filter { it.start >= nowSec - 20 * 60 && it.start < tonightEnd }
                if (window.size < 15) window = all.filter { it.start >= nowSec - 20 * 60 && it.start < nowSec + 24 * 3600 }
                // Pro Titel nur die nächste Ausstrahlung
                val byTitle = window.sortedBy { it.start }.groupBy { normName(it.title) }
                val shows = byTitle.values.map { list ->
                    val e = list.first()
                    val type = Epg.typeOf(e)
                    val kind = Epg.kindOf(e)
                    val overview = listOf(e.subTitle, e.desc).filter { it.isNotBlank() }.joinToString(" – ")
                    TvShow(
                        CatalogItem(0, type, e.title, "", e.icon, overview, 0.0, listOf(kind)),
                        e.channel, e.public, kind, e.start, list.size,
                    )
                }
                tvToday.value = TvState(items = rankToday(shows), loading = true)
                // Die besten Treffer mit Postern, Genres & Trailern aus der Filmdatenbank anreichern
                val top = tvToday.value.items.take(30)
                val enriched = HashMap<String, TvShow>()
                for (r in top) {
                    val s = r.show
                    val hit = runCatching { TitleLookup.find(s.item.title) }.getOrNull() ?: continue
                    if (hit.type != s.item.type && s.item.type == "movie" && hit.type == "tv") continue
                    enriched[s.item.key] = s.copy(
                        item = hit.copy(
                            poster = hit.poster ?: s.item.poster,
                            overview = s.item.overview.ifBlank { hit.overview },
                            genres = (hit.genres + s.kind).distinct(),
                        )
                    )
                }
                tvToday.value = TvState(items = rankToday(shows.map { enriched[it.item.key] ?: it }))
            } catch (e: Exception) {
                tvToday.value = TvState(error = e.localizedMessage ?: "Programmführer konnte nicht geladen werden")
            }
        }
    }

    private fun rankToday(shows: List<TvShow>): List<TvRec> {
        val (taste, example) = tasteOfBoth()
        val u = uid.value
        val p = household.value?.partnerOf(u)
        val lib = titles.value.associateBy { normName(it.title) }
        val maxTaste = taste.values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
        val zone = java.time.ZoneId.of("Europe/Berlin")
        fun tasteFor(g: String): Double = taste[g] ?: taste.entries
            .filter { it.key.length >= 4 && g.length >= 4 && (it.key.contains(g, true) || g.contains(it.key, true)) }
            .sumOf { it.value }
        return shows.map { s ->
            val known = lib[normName(s.item.title)]
            val best = listOfNotNull(known?.rating(u), known?.rating(p)).maxOrNull()
            val genres = s.item.genres.distinct()
            val genreScore = genres.sumOf { tasteFor(it) / maxTaste }
            val topGenre = genres.filter { tasteFor(it) > 0 }.maxByOrNull { tasteFor(it) }
            val time = java.time.Instant.ofEpochSecond(s.nextAir).atZone(zone).toLocalTime()
            val prime = time >= java.time.LocalTime.of(19, 30) && time <= java.time.LocalTime.of(22, 30)
            var score = genreScore * 3 + (if (prime) 2.0 else 0.0) + (if (s.item.type == "movie") 1.0 else 0.0) +
                (if (s.item.poster != null) 0.5 else 0.0)
            val reason = when {
                best != null && best >= 7 -> { score += 10; "Habt ihr mit $best/10 bewertet – läuft heute wieder" }
                best != null && best <= 4 -> { score -= 10; "Fandet ihr nicht so gut" }
                known?.isRejected(p) == true -> { score -= 8; "Nicht ganz euer Fall" }
                topGenre != null -> "Ihr mögt $topGenre" + (example[topGenre]?.let { " – wie „$it“" } ?: "")
                prime -> "Läuft heute zur besten Sendezeit"
                else -> "Läuft heute"
            }
            score += dayJitter(s.item.title) * 2.5
            TvRec(s, score, reason)
        }.sortedByDescending { it.score }.let { diversify(it) { r -> r.show.kind } }
    }

    /** Zufallswert 0..1, der pro Titel und Tag gleich bleibt (ändert sich jeden Tag). */
    private fun dayJitter(key: String): Double {
        val seed = (key + java.time.LocalDate.now()).hashCode().toLong()
        return java.util.Random(seed).nextDouble()
    }

    /**
     * Mehr Abwechslung: nie mehr als zwei Sendungen derselben Art (Krimi, Doku, Show …)
     * direkt hintereinander – die Reihenfolge nach Geschmack bleibt sonst erhalten.
     */
    private fun <T> diversify(list: List<T>, kindOf: (T) -> String): List<T> {
        val rest = list.toMutableList()
        val out = ArrayList<T>(list.size)
        while (rest.isNotEmpty()) {
            val last = out.takeLast(2).map(kindOf)
            val blocked = if (last.size == 2 && last[0] == last[1]) last[0] else null
            val i = rest.indexOfFirst { kindOf(it) != blocked }.takeIf { it >= 0 } ?: 0
            out += rest.removeAt(i)
        }
        return out
    }

    /** Beliebte Filme & Serien eines Genres – zum Durchstöbern und Bewerten. */
    fun loadGenre(genre: String, force: Boolean = false) {
        if (!force && genreLoaded == genre && genreList.value.items.isNotEmpty()) return
        genreLoaded = genre
        genreList.value = RecState(loading = true)
        viewModelScope.launch {
            genreList.value = try {
                val movies = async { runCatching { JW.byGenre(genre, "movie", 50) }.getOrDefault(emptyList()) }
                val shows = async { runCatching { JW.byGenre(genre, "tv", 50) }.getOrDefault(emptyList()) }
                val a = movies.await(); val b = shows.await()
                val mixed = ArrayList<CatalogItem>()
                for (i in 0 until maxOf(a.size, b.size)) { if (i < a.size) mixed += a[i]; if (i < b.size) mixed += b[i] }
                if (mixed.isEmpty()) throw IllegalStateException("Gerade nicht erreichbar – bitte später nochmal.")
                RecState(items = mixed.distinctBy { it.key }.map { Rec(it, 0.0, it.genres.take(3).joinToString(", ")) })
            } catch (e: Exception) {
                RecState(error = e.localizedMessage)
            }
        }
    }

    /**
     * Gesehene Titel aus der WhatsApp-Liste (assets/whatsapp_gesehen.json) übernehmen:
     * Jeder Titel wird für euch beide als gesehen abgehakt.
     */
    fun importWhatsApp(onDone: (Int) -> Unit = {}) = run {
        var count = 0
        try { count = doImportWhatsApp() } finally { onDone(count) }
    }

    private suspend fun doImportWhatsApp(): Int {
        val ref = hhRef() ?: throw IllegalStateException("Erst die gemeinsame Liste verbinden.")
        val u = uid.value ?: return 0
        val people = listOfNotNull(u, household.value?.partnerOf(u))
        val json = getApplication<Application>().assets.open("whatsapp_gesehen.json").bufferedReader().use { it.readText() }
        val arr = org.json.JSONArray(json)
        val now = System.currentTimeMillis()
        var count = 0
        val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        for (chunk in items.chunked(400)) {
            db.runBatch { b ->
                for (o in chunk) {
                    val title = o.optString("title")
                    if (title.isBlank()) continue
                    val type = o.optString("type", "movie")
                    val id = o.optInt("id").takeIf { it != 0 }
                        ?: -((("mt:" + normName(title)).hashCode() and 0x3fffffff) + 1)
                    val genres = o.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.optString(it) } } ?: emptyList()
                    val data = hashMapOf<String, Any?>(
                        "mediaId" to id, "type" to type, "title" to title,
                        "year" to o.optString("year", ""), "poster" to o.optString("poster", ""),
                        "overview" to o.optString("overview", ""),
                        "watched" to people.associateWith { true },
                        "watchedAt" to people.associateWith { now },
                        "source" to "WhatsApp",
                    )
                    if (genres.isNotEmpty()) data["genres"] = genres
                    if (titleFor(keyOf(type, id)) == null) data["addedAt"] = now
                    b.set(ref.collection("titles").document(keyOf(type, id)), data, SetOptions.merge())
                    count++
                }
            }.await()
        }
        prefs.edit().putBoolean("waImported", true).apply()
        message.value = "$count Titel aus WhatsApp als gesehen abgehakt ✓"
        return count
    }

    fun waImported() = prefs.getBoolean("waImported", false)

    private var posterJob: kotlinx.coroutines.Job? = null
    private val posterTried = HashSet<String>()

    /** Für Titel ohne Bild automatisch eins suchen und für euch beide speichern. */
    fun fillMissingPosters() {
        if (posterJob?.isActive == true) return
        posterJob = viewModelScope.launch {
            val ref = hhRef() ?: return@launch
            while (true) {
                val next = titles.value.firstOrNull { it.poster.isNullOrBlank() && !it.posterChecked && it.key !in posterTried } ?: break
                posterTried += next.key
                val img = runCatching { PosterFinder.find(next.title, next.type) }.getOrNull()
                runCatching {
                    ref.collection("titles").document(next.key).set(
                        if (img != null) mapOf("poster" to img) else mapOf("posterChecked" to true),
                        SetOptions.merge()
                    ).await()
                }
                kotlinx.coroutines.delay(1200)
            }
        }
    }
}
