package de.marween.filmabend

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

enum class RecMode { BOTH, ME, PARTNER }

data class Rec(val item: TmdbItem, val score: Double, val reason: String)

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
    val profile = MutableStateFlow<Profile?>(null)
    val profileLoaded = MutableStateFlow(false)
    val household = MutableStateFlow<Household?>(null)
    val titles = MutableStateFlow<List<Title>>(emptyList())
    val suggestions = MutableStateFlow<List<Suggestion>>(emptyList())
    val recs = MutableStateFlow(RecState())
    val message = MutableStateFlow<String?>(null)

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
        userReg = db.collection("users").document(u).addSnapshotListener { snap, err ->
            if (err != null) { message.value = "Profil konnte nicht geladen werden: ${err.message}"; profileLoaded.value = true; return@addSnapshotListener }
            val p = if (snap != null && snap.exists()) Profile(snap.getString("name") ?: "", snap.getString("hid")) else null
            profile.value = p
            profileLoaded.value = true
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
        hhRegs += ref.addSnapshotListener { s, _ ->
            if (s != null && s.exists()) {
                household.value = Household(
                    id = s.id,
                    code = s.getString("code") ?: "",
                    members = (s.get("members") as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                    names = (s.get("names") as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v.toString() } ?: emptyMap(),
                )
            }
        }
        hhRegs += ref.collection("titles").addSnapshotListener { q, _ ->
            if (q != null) titles.value = q.documents.mapNotNull { Title.from(it) }
        }
        hhRegs += ref.collection("suggestions").addSnapshotListener { q, _ ->
            if (q != null) suggestions.value = q.documents.mapNotNull { Suggestion.from(it) }.sortedByDescending { it.createdAt }
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
            "tmdbId" to i.tmdbId,
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
        ref.collection("suggestions").add(mapOf(
            "from" to u, "to" to to, "key" to i.key, "tmdbId" to i.tmdbId, "type" to i.type,
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
        if (accept) setWatchlist(TitleInfo(s.tmdbId, s.type, s.title, s.year, s.poster, ""), true)
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

    fun loadRecs(mode: RecMode) {
        val u = uid.value ?: return
        val p = household.value?.partnerOf(u)
        val people = when (mode) {
            RecMode.ME -> listOf(u)
            RecMode.PARTNER -> listOfNotNull(p)
            RecMode.BOTH -> listOfNotNull(u, p)
        }
        if (!Tmdb.configured) {
            recs.value = RecState(error = "Für Vorschläge fehlt der TMDB-Schlüssel (siehe Anleitung).")
            return
        }
        recs.value = recs.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val lib = titles.value
                // Was haben die Personen gut bewertet? Gewicht: 7 -> 1, 10 -> 4
                data class Source(val t: Title, val weight: Double, val person: String)
                val sources = lib.flatMap { t ->
                    people.mapNotNull { who -> t.rating(who)?.takeIf { it >= 7 }?.let { Source(t, (it - 6).toDouble(), who) } }
                }
                val bySource = sources.groupBy { it.t.key }
                    .map { (_, s) -> s.first().t to s }
                    .sortedByDescending { (_, s) -> s.sumOf { it.weight } }
                    .take(12)

                val exclude = lib.filter { t -> people.any { t.isWatched(it) } }.map { it.key }.toSet()
                val disliked = lib.filter { t -> people.any { (t.rating(it) ?: 10) <= 3 } }.map { it.key }.toSet()

                if (bySource.isEmpty()) {
                    val trending = Tmdb.trending().filter { it.key !in exclude }
                    recs.value = RecState(items = trending.map { Rec(it, it.vote, "Gerade angesagt") }, fallback = true)
                    return@launch
                }

                val results = bySource.map { (t, s) ->
                    async { runCatching { Triple(t, s, Tmdb.recommendations(t.type, t.tmdbId)) }.getOrNull() }
                }.awaitAll().filterNotNull()

                val score = HashMap<String, Double>()
                val items = HashMap<String, TmdbItem>()
                val reasonTitle = HashMap<String, Pair<String, Double>>()
                val fans = HashMap<String, MutableSet<String>>()
                for ((t, s, list) in results) {
                    val w = s.sumOf { it.weight }
                    list.forEachIndexed { idx, item ->
                        if (item.key in exclude || item.key in disliked) return@forEachIndexed
                        val add = w * (1.0 - idx * 0.03).coerceAtLeast(0.3)
                        score[item.key] = (score[item.key] ?: 0.0) + add
                        items[item.key] = item
                        fans.getOrPut(item.key) { mutableSetOf() } += s.map { it.person }
                        val best = reasonTitle[item.key]
                        if (best == null || add > best.second) reasonTitle[item.key] = t.title to add
                    }
                }
                val h = household.value
                val ranked = score.entries.map { (k, sc) ->
                    // Passt zu beiden? Dann Bonus.
                    val bonus = if (mode == RecMode.BOTH && (fans[k]?.size ?: 0) >= 2) 1.5 else 1.0
                    val item = items.getValue(k)
                    val reasonName = reasonTitle[k]?.first ?: ""
                    val who = when {
                        mode == RecMode.BOTH && (fans[k]?.size ?: 0) >= 2 -> "Passt zu euch beiden"
                        mode == RecMode.BOTH -> {
                            val person = fans[k]?.firstOrNull()
                            if (person == u) "Weil du „$reasonName“ mochtest" else "Weil ${h?.nameOf(person)} „$reasonName“ mochte"
                        }
                        mode == RecMode.ME -> "Weil du „$reasonName“ mochtest"
                        else -> "Weil ${h?.nameOf(p)} „$reasonName“ mochte"
                    }
                    Rec(item, sc * bonus + item.vote / 10.0, who)
                }.sortedByDescending { it.score }.take(60)
                recs.value = RecState(items = ranked)
            } catch (e: Exception) {
                recs.value = RecState(error = e.localizedMessage ?: "Vorschläge konnten nicht geladen werden")
            }
        }
    }
}
