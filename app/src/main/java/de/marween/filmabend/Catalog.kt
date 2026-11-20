package de.marween.filmabend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Ein Film oder eine Serie aus den Online-Katalogen.
 * Filme kommen aus dem deutschen iTunes-Store (Apple), Serien von TVmaze.
 * Beide brauchen keinen Schlüssel und keine Anmeldung.
 *
 * Bei Serien aus den iTunes-Charts kennen wir die TVmaze-Nummer noch nicht (id = 0);
 * die wird beim Antippen über [Catalog.resolve] nachgeschlagen.
 */
data class CatalogItem(
    val id: Int,
    val type: String,
    val title: String,
    val year: String,
    val poster: String?,
    val overview: String,
    val vote: Double,
    val genres: List<String> = emptyList(),
    val trailer: String? = null,   // Apple-Vorschau (deutsch) – nur bei Filmen
    val extra: String = "",        // z. B. „ab 25.11. bei Netflix“
    val weight: Int = 0,
    val yt: String? = null,        // YouTube-Trailer (Video-ID), z. B. von JustWatch
) {
    val ytUrl get() = yt?.let { "https://www.youtube.com/watch?v=$it" }
    val key get() = if (id == 0) "tvname_${normName(title)}" else keyOf(type, id)
    val resolved get() = id != 0
    val info get() = TitleInfo(id, type, title, year, poster, overview, genres)
}

data class CatalogDetail(
    val item: CatalogItem,
    val genres: List<String>,
    val seasons: Int,
    val runtime: Int,
    val network: String,
    val status: String,
    val providers: List<String> = emptyList(),
) {
    val info get() = item.info.copy(genres = genres, seasons = seasons)
}

fun normName(s: String) = s.lowercase().replace(Regex("[^a-z0-9äöüß]"), "")

object Catalog {

    // ---------- Genres vereinheitlichen (deutsche Namen) ----------

    /** Macht aus iTunes- und TVmaze-Genres einheitliche deutsche Namen. */
    fun genre(raw: String): String {
        val g = raw.lowercase()
        return when {
            "anime" in g || "animation" in g || "trick" in g -> "Animation"
            "action" in g -> "Action"
            "abenteuer" in g || "adventure" in g -> "Abenteuer"
            "komöd" in g || "comedy" in g || "kömod" in g -> "Komödie"
            "doku" in g || "documentary" in g || "nonfiction" in g -> "Doku"
            "horror" in g -> "Horror"
            "thriller" in g || "espionage" in g -> "Thriller"
            "krimi" in g || "crime" in g || "legal" in g -> "Krimi"
            "mystery" in g || "supernatural" in g -> "Mystery"
            "science" in g || "sci-fi" in g || "fantasy" in g -> "Sci-Fi & Fantasy"
            "roman" in g || "liebe" in g -> "Romantik"
            "kinder" in g || "famil" in g || "children" in g -> "Familie"
            "western" in g -> "Western"
            "krieg" in g || "war" == g || "history" in g || "geschicht" in g -> "Geschichte & Krieg"
            "musik" in g || "music" in g -> "Musik"
            "sport" in g -> "Sport"
            "reality" in g -> "Reality-TV"
            "drama" in g || "medical" in g -> "Drama"
            "liebes" in g -> "Romantik"
            "klassik" in g || "classic" in g -> "Klassiker"
            else -> raw
        }
    }

    /** iTunes-Genre-Nummern für die deutschen Film-Charts. */
    private val movieGenreIds = mapOf(
        "Action" to 4401, "Abenteuer" to 4401, "Animation" to 4402, "Klassiker" to 4403,
        "Komödie" to 4404, "Doku" to 4405, "Drama" to 4406, "Horror" to 4408, "Familie" to 4410,
        "Romantik" to 4412, "Sci-Fi & Fantasy" to 4413, "Thriller" to 4416, "Krimi" to 4416,
        "Mystery" to 4416, "Western" to 4418, "Sport" to 4417,
    )

    /** iTunes-Genre-Nummern für die deutschen Serien-Charts. */
    private val tvGenreIds = mapOf(
        "Komödie" to 4000, "Drama" to 4001, "Krimi" to 4001, "Thriller" to 4001, "Mystery" to 4001,
        "Horror" to 4001, "Romantik" to 4001, "Geschichte & Krieg" to 4001,
        "Animation" to 4002, "Action" to 4003, "Abenteuer" to 4003, "Familie" to 4005,
        "Doku" to 4006, "Reality-TV" to 4007, "Sci-Fi & Fantasy" to 4008,
    )

    // ---------- HTTP ----------

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "Filmabend-App (Android)")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("Filmdaten nicht erreichbar (Fehler $code)")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")
    private fun JSONObject.label(k: String): String = optJSONObject(k)?.str("label") ?: ""
    private fun stripHtml(s: String) = s.replace(Regex("<[^>]*>"), "").replace("&amp;", "&")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ").trim()

    /** Apple-Bilder in beliebiger Größe anfordern. */
    private fun bigArt(url: String, size: String): String? =
        url.takeIf { it.isNotBlank() }?.replace(Regex("/\\d+x\\d+bb(-\\d+)?\\.(png|jpg|jpeg)$"), "/${size}bb.jpg")

    // ---------- Filme (iTunes) ----------

    private fun parseMovie(o: JSONObject): CatalogItem? {
        val id = o.optLong("trackId").toInt().takeIf { it != 0 } ?: return null
        val title = o.str("trackName").ifBlank { return null }
        return CatalogItem(
            id = id,
            type = "movie",
            title = title,
            year = o.str("releaseDate").take(4),
            poster = bigArt(o.str("artworkUrl100"), "400x600"),
            overview = o.str("longDescription").ifBlank { o.str("shortDescription") },
            vote = 0.0,
            genres = listOf(o.str("primaryGenreName")).filter { it.isNotBlank() }.map { genre(it) },
            trailer = o.str("previewUrl").ifBlank { null },
        )
    }

    private suspend fun searchMovies(q: String): List<CatalogItem> {
        val json = fetch("https://itunes.apple.com/search?term=${enc(q)}&country=de&lang=de_de&media=movie&entity=movie&limit=25")
        val arr = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::parseMovie) }
    }

    private suspend fun movieDetail(id: Int): CatalogDetail {
        val json = fetch("https://itunes.apple.com/lookup?id=$id&country=de&lang=de_de")
        val o = JSONObject(json).optJSONArray("results")?.optJSONObject(0) ?: throw IOException("Film nicht gefunden")
        val item = parseMovie(o) ?: throw IOException("Film nicht gefunden")
        return CatalogDetail(
            item = item,
            genres = item.genres,
            seasons = 0,
            runtime = (o.optLong("trackTimeMillis") / 60000).toInt(),
            network = "",
            status = "",
        )
    }

    // ---------- Serien (TVmaze) ----------

    private fun parseShow(o: JSONObject): CatalogItem? {
        val id = o.optInt("id").takeIf { it != 0 } ?: return null
        val title = o.str("name").ifBlank { return null }
        val img = o.optJSONObject("image")
        val g = o.optJSONArray("genres")
        return CatalogItem(
            id = id,
            type = "tv",
            title = title,
            year = o.str("premiered").take(4),
            poster = img?.str("medium")?.ifBlank { null } ?: img?.str("original")?.ifBlank { null },
            overview = stripHtml(o.str("summary")),
            vote = o.optJSONObject("rating")?.optDouble("average", 0.0)?.takeIf { !it.isNaN() } ?: 0.0,
            genres = if (g == null) emptyList() else (0 until g.length()).map { genre(g.optString(it)) }.distinct(),
        )
    }

    private suspend fun searchShows(q: String): List<CatalogItem> {
        val arr = JSONArray(fetch("https://api.tvmaze.com/search/shows?q=${enc(q)}"))
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optJSONObject("show")?.let(::parseShow) }
    }

    private suspend fun showDetail(id: Int): CatalogDetail {
        val o = JSONObject(fetch("https://api.tvmaze.com/shows/$id?embed=seasons"))
        val item = parseShow(o) ?: throw IOException("Serie nicht gefunden")
        val seasons = o.optJSONObject("_embedded")?.optJSONArray("seasons")?.let { a ->
            (0 until a.length()).count { (a.optJSONObject(it)?.optInt("number", 0) ?: 0) > 0 }
        } ?: 0
        val network = o.optJSONObject("webChannel")?.str("name")?.ifBlank { null }
            ?: o.optJSONObject("network")?.str("name") ?: ""
        val status = when (o.str("status")) {
            "Running" -> "läuft noch"
            "Ended" -> "abgeschlossen"
            "To Be Determined" -> "Zukunft offen"
            "In Development" -> "in Entwicklung"
            else -> ""
        }
        val runtime = o.optInt("averageRuntime").takeIf { it > 0 } ?: o.optInt("runtime")
        return CatalogDetail(item, item.genres, seasons, runtime, network, status)
    }

    /** Für Serien aus den iTunes-Charts die passende TVmaze-Serie suchen. */
    suspend fun resolve(item: CatalogItem): CatalogItem {
        if (item.resolved) return item
        val o = JSONObject(fetch("https://api.tvmaze.com/singlesearch/shows?q=${enc(item.title)}"))
        return parseShow(o) ?: throw IOException("Serie „${item.title}“ nicht gefunden")
    }

    // ---------- Öffentliche Funktionen ----------

    /** Sucht Filme und Serien – zuerst bei JustWatch (deutsch, mit Postern), sonst iTunes/TVmaze. */
    suspend fun search(q: String): List<CatalogItem> {
        val jw = runCatching { JW.search(q) }.getOrNull()
        if (!jw.isNullOrEmpty()) return jw
        return searchOld(q)
    }

    private suspend fun searchOld(q: String): List<CatalogItem> = coroutineScope {
        val m = async { runCatching { searchMovies(q) } }
        val s = async { runCatching { searchShows(q) } }
        val movies = m.await(); val shows = s.await()
        if (movies.isFailure && shows.isFailure) throw (movies.exceptionOrNull() ?: IOException("Keine Verbindung"))
        val a = movies.getOrDefault(emptyList()); val b = shows.getOrDefault(emptyList())
        // Abwechselnd mischen, damit beides oben auftaucht
        val out = ArrayList<CatalogItem>()
        for (i in 0 until maxOf(a.size, b.size)) {
            if (i < b.size) out += b[i]
            if (i < a.size) out += a[i]
        }
        // Exakte Titel-Treffer nach oben
        val nq = normName(q)
        out.sortedByDescending { if (normName(it.title) == nq) 1 else 0 }.distinctBy { it.key }
    }

    suspend fun details(type: String, id: Int): CatalogDetail = when {
        JW.isJw(id) -> JW.detail(id, type)
        type == "tv" -> showDetail(id)
        else -> movieDetail(id)
    }

    /** Neue Filme: aktuelle deutsche iTunes-Charts, nur Titel der letzten 12 Monate, mit Trailer. */
    suspend fun newMovies(): List<CatalogItem> {
        val cutoff = java.time.LocalDate.now().minusMonths(12).year * 10000 + java.time.LocalDate.now().monthValue * 100
        return charts("movie", null, 100).filter { m ->
            // extra = "Erschienen TT.MM.JJJJ"
            val d = m.extra.removePrefix("Erschienen ").split(".")
            val num = if (d.size == 3) (d[2].toIntOrNull() ?: 0) * 10000 + (d[1].toIntOrNull() ?: 0) * 100 else 0
            num >= cutoff
        }
    }

    /**
     * Neue Serien: Staffel-1-Starts bei Streamingdiensten (Netflix, Prime, Disney+ …)
     * der letzten 10 und nächsten 14 Tage, laut TVmaze-Programm.
     */
    suspend fun newShows(): List<CatalogItem> = coroutineScope {
        val today = java.time.LocalDate.now()
        val fmt = java.time.format.DateTimeFormatter.ofPattern("dd.MM.")
        val days = (-10..14).map { today.plusDays(it.toLong()) }
        val found = LinkedHashMap<Int, CatalogItem>()
        for (chunk in days.chunked(6)) {
            val arrays = chunk.map { d -> async { d to runCatching { JSONArray(fetch("https://api.tvmaze.com/schedule/web?date=$d")) }.getOrNull() } }
            for ((d, arr) in arrays.map { it.await() }) {
                if (arr == null) continue
                for (i in 0 until arr.length()) {
                    val ep = arr.optJSONObject(i) ?: continue
                    if (ep.optInt("season") != 1 || ep.optInt("number") != 1) continue
                    val show = ep.optJSONObject("_embedded")?.optJSONObject("show") ?: continue
                    val lang = show.str("language")
                    if (lang != "English" && lang != "German") continue
                    val item = parseShow(show) ?: continue
                    if (item.poster == null || found.containsKey(item.id)) continue
                    val where = show.optJSONObject("webChannel")?.str("name") ?: ""
                    val whenText = (if (d.isAfter(today)) "ab " else if (d == today) "seit heute" else "seit ") +
                        (if (d == today) "" else d.format(fmt))
                    found[item.id] = item.copy(
                        extra = listOf("Neu $whenText".trim(), where).filter { it.isNotBlank() }.joinToString(" bei "),
                        weight = show.optInt("weight"),
                    )
                }
            }
            kotlinx.coroutines.delay(700) // TVmaze erlaubt nur ~20 Abrufe pro 10 Sekunden
        }
        found.values.sortedByDescending { it.weight }
    }

    /** YouTube-Suche nach dem deutschen Trailer. */
    fun youtubeTrailer(title: String, year: String = ""): String =
        "https://www.youtube.com/results?search_query=" + enc("$title ${year.take(4)} Trailer Deutsch".replace("  ", " "))

    /** Aktuelle iTunes-Charts (Deutschland), optional für ein Genre. */
    suspend fun charts(type: String, genre: String? = null, limit: Int = 50): List<CatalogItem> {
        val gid = genre?.let { if (type == "tv") tvGenreIds[it] else movieGenreIds[it] }
        if (genre != null && gid == null) return emptyList()
        val feed = if (type == "tv") "toptvseasons" else "topmovies"
        val g = if (gid != null) "/genre=$gid" else ""
        val root = JSONObject(fetch("https://itunes.apple.com/de/rss/$feed/limit=$limit$g/json")).optJSONObject("feed")
            ?: return emptyList()
        val entries: List<JSONObject> = when (val e = root.opt("entry")) {
            is JSONArray -> (0 until e.length()).mapNotNull { e.optJSONObject(it) }
            is JSONObject -> listOf(e)
            else -> emptyList()
        }
        return entries.mapNotNull { o ->
            val imgs = o.optJSONArray("im:image")
            val img = imgs?.optJSONObject(imgs.length() - 1)?.str("label") ?: ""
            val cat = o.optJSONObject("category")?.optJSONObject("attributes")?.str("label") ?: ""
            val year = o.optJSONObject("im:releaseDate")?.str("label")?.take(4) ?: ""
            if (type == "tv") {
                val name = o.label("im:artist").ifBlank { o.label("im:name").substringBefore(",") }
                if (name.isBlank()) null else CatalogItem(
                    id = 0, type = "tv", title = name, year = "", poster = bigArt(img, "600x600"),
                    overview = "", vote = 0.0, genres = listOf(genre(cat)),
                )
            } else {
                val id = o.optJSONObject("id")?.optJSONObject("attributes")?.str("im:id")?.toIntOrNull() ?: return@mapNotNull null
                val links = o.opt("link")
                val linkList = when (links) {
                    is JSONArray -> (0 until links.length()).mapNotNull { links.optJSONObject(it) }
                    is JSONObject -> listOf(links)
                    else -> emptyList()
                }
                val trailer = linkList.mapNotNull { it.optJSONObject("attributes") }
                    .firstOrNull { it.str("rel") == "enclosure" }?.str("href")?.ifBlank { null }
                val dateLabel = o.optJSONObject("im:releaseDate")?.optJSONObject("attributes")?.str("label") ?: ""
                CatalogItem(
                    id = id, type = "movie", title = o.label("im:name"), year = year,
                    poster = bigArt(img, "400x600"), overview = o.label("summary"), vote = 0.0,
                    genres = listOf(genre(cat)), trailer = trailer,
                    extra = if (dateLabel.isNotBlank()) "Erschienen $dateLabel" else "",
                )
            }
        }.distinctBy { it.key }
    }
}

// ======================= Öffentlich-rechtliche Mediatheken =======================

/** Ein Film oder eine Serie aus ARD, ZDF, arte, 3sat, WDR, SWR, NDR, BR … (über MediathekViewWeb). */
data class MediathekItem(
    val channel: String,
    val topic: String,
    val title: String,
    val description: String,
    val timestamp: Long,
    val durationMin: Int,
    val video: String,
    val website: String,
    val isSeries: Boolean,
    val season: Int = 0,
    val episodes: Int = 1,
    val trailer: String? = null,
) {
    /** Name, unter dem es in eurer Bibliothek landet. */
    val name get() = if (isSeries) topic.trim() else title
    /** Eigene Nummer (negativ), damit es nicht mit iTunes/TVmaze kollidiert. */
    val mediaId get() = -((("mt:" + normName(name)).hashCode() and 0x3fffffff) + 1)
    val type get() = if (isSeries) "tv" else "movie"
    val key get() = keyOf(type, mediaId)
    val info get() = TitleInfo(mediaId, type, name, "", null, description, listOf("Mediathek $channel"))
}

object Mediathek {
    private val channels = setOf(
        "ARD", "ZDF", "ZDFNEO", "ZDF-TIVI", "KIKA", "ARTE.DE", "3SAT", "BR", "HR", "MDR", "NDR",
        "RBB", "SR", "SWR", "WDR", "ONE", "ARD-ALPHA", "PHOENIX", "FUNK.NET", "RADIO BREMEN TV",
    )
    private val skipWords = listOf("audiodeskription", "gebärdensprache", "hörfassung", "originalversion", "(ov)", "klare sprache")
    private val skipTopics = listOf(
        "tagesschau", "heute", "nachrichten", "magazin", "sport", "fußball", "champions league", "volle kanne",
        "talk", "lanz", "maischberger", "wetter", "morgenmagazin", "mittagsmagazin", "gottesdienst", "börse",
    )

    private suspend fun query(field: String?, q: String?, minSec: Int?, maxSec: Int?, size: Int): List<JSONObject> =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("queries", JSONArray().apply {
                    if (field != null && q != null) put(JSONObject().put("fields", JSONArray().put(field)).put("query", q))
                })
                put("sortBy", "timestamp"); put("sortOrder", "desc"); put("future", false)
                if (minSec != null) put("duration_min", minSec)
                if (maxSec != null) put("duration_max", maxSec)
                put("offset", 0); put("size", size)
            }.toString()
            val conn = URL("https://mediathekviewweb.de/api/query").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.setRequestProperty("Content-Type", "text/plain")
            conn.setRequestProperty("User-Agent", "Filmabend-App (Android)")
            try {
                conn.outputStream.use { it.write(body.toByteArray()) }
                if (conn.responseCode !in 200..299) throw IOException("Mediatheken nicht erreichbar (Fehler ${conn.responseCode})")
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONObject(text).optJSONObject("result")?.optJSONArray("results") ?: JSONArray()
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            } finally {
                conn.disconnect()
            }
        }

    private fun JSONObject.s(k: String) = if (isNull(k)) "" else optString(k, "")
    private fun ok(o: JSONObject): Boolean {
        val t = o.s("title").lowercase()
        val topic = o.s("topic").lowercase()
        return o.s("channel").uppercase() in channels &&
            skipWords.none { it in t } &&
            skipTopics.none { it in topic } &&
            o.s("url_video").isNotBlank()
    }
    private fun clean(title: String) = title
        .replace(Regex("\\s*\\((S\\d+/E\\d+|Folge \\d+)\\)"), "")
        .replace(Regex("\\s*\\|\\s*Film\\s*$"), "")
        .trim()

    private suspend fun trailers(): List<JSONObject> =
        runCatching { query("title", "trailer", null, 400, 300) }.getOrDefault(emptyList()).filter { ok(it) }

    private fun findTrailer(trailers: List<JSONObject>, vararg names: String): String? {
        val wanted = names.map { normName(it) }.filter { it.length >= 4 }
        return trailers.firstOrNull { t ->
            val tn = normName(t.s("title")); val tp = normName(t.s("topic"))
            wanted.any { w -> w == tp || tn.contains(w) }
        }?.s("url_video")?.ifBlank { null }
    }

    /** Neue Spielfilme und TV-Filme (inkl. Tatort & Polizeiruf) aus den Mediatheken. */
    suspend fun films(): List<MediathekItem> = coroutineScope {
        val a = async { runCatching { query("topic", "film", 4500, null, 250) }.getOrDefault(emptyList()) }
        val b = async { runCatching { query("topic", "tatort", 4800, null, 40) }.getOrDefault(emptyList()) }
        val c = async { runCatching { query("topic", "polizeiruf", 4800, null, 30) }.getOrDefault(emptyList()) }
        val tr = async { trailers() }
        val all = (a.await() + b.await() + c.await()).filter { ok(it) }.sortedByDescending { it.optLong("timestamp") }
        if (all.isEmpty()) throw IOException("Mediatheken gerade nicht erreichbar.")
        val trailerList = tr.await()
        all.distinctBy { normName(clean(it.s("title"))) }.take(120).map { o ->
            val title = clean(o.s("title"))
            MediathekItem(
                channel = o.s("channel"), topic = o.s("topic"), title = title, description = o.s("description"),
                timestamp = o.optLong("timestamp"), durationMin = o.optInt("duration") / 60,
                video = o.s("url_video"), website = o.s("url_website"), isSeries = false,
                trailer = findTrailer(trailerList, title),
            )
        }
    }

    /** Serien, von denen gerade neue Folgen in den Mediatheken sind – neue Serien (Staffel 1) zuerst. */
    suspend fun series(): List<MediathekItem> = coroutineScope {
        val tr = async { trailers() }
        val pages = listOf(0, 1).map { p ->
            async {
                runCatching {
                    // zwei Seiten à 500 neueste Folgen zwischen 20 und 70 Minuten
                    if (p == 0) query(null, null, 1200, 4200, 500)
                    else query("title", "folge", 1200, 4200, 500)
                }.getOrDefault(emptyList())
            }
        }.map { it.await() }.flatten().filter { ok(it) }
        if (pages.isEmpty()) throw IOException("Mediatheken gerade nicht erreichbar.")
        val epRegex = Regex("\\(S(\\d{1,2})/E(\\d+)\\)")
        data class Ep(val o: JSONObject, val season: Int, val ep: Int)
        val eps = pages.mapNotNull { o ->
            val m = epRegex.find(o.s("title")) ?: return@mapNotNull null
            Ep(o, m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }.distinctBy { it.o.s("url_video") }
        val trailerList = tr.await()
        eps.groupBy { normName(it.o.s("topic")) }.values.map { list ->
            val newest = list.maxBy { it.o.optLong("timestamp") }
            val season = newest.season
            val first = list.filter { it.season == season }.minBy { it.ep }
            val topic = newest.o.s("topic").trim()
            MediathekItem(
                channel = newest.o.s("channel"), topic = topic, title = clean(first.o.s("title")),
                description = first.o.s("description").ifBlank { newest.o.s("description") },
                timestamp = newest.o.optLong("timestamp"), durationMin = first.o.optInt("duration") / 60,
                video = first.o.s("url_video"), website = first.o.s("url_website"), isSeries = true,
                season = season, episodes = list.count { it.season == season },
                trailer = findTrailer(trailerList, topic),
            )
        }.sortedWith(compareByDescending<MediathekItem> { it.season == 1 }.thenByDescending { it.timestamp })
    }
}

// ======================= Deutsche Trailer (offizielle YouTube-Kanäle) =======================

data class TrailerItem(
    val videoId: String,
    val title: String,
    val show: String,
    val provider: String,
    val published: Long,
    val thumb: String,
    val description: String,
) {
    val url get() = "https://www.youtube.com/watch?v=$videoId"
}

/**
 * Neueste deutsche Trailer von den offiziellen YouTube-Kanälen der Streamingdienste.
 * Nutzt die öffentlichen Kanal-Feeds von YouTube – kein Schlüssel nötig.
 */
object Trailers {
    /** Anzeigename -> YouTube-Kanal-ID */
    val channels = linkedMapOf(
        "Netflix" to "UCZqgRlLcvO3Fnx_npQJygcQ",
        "Prime Video" to "UCNJwYVhTNX23AULBnfwnc9A",
        "Disney+" to "UCOJJq47ie4y0HC5hH4JSQ6w",
        "Paramount+" to "UCli6wrnHj-ZRTChDbTGzT-Q",
        "Sky / WOW" to "UCo4jMxp607FOEolnBUsEDkA",
        "RTL+" to "UC58J5mgyNYw1IS947I73qMw",
        "ZDF" to "UC_EnhS-vNDc6Eo9nyQHC2YQ",
        "ZDFneo" to "UCk9uI40bPBRkC7lo6y8wQ7Q",
        "ARD" to "UCqmQ1b96-PNH4coqgHTuTlA",
        "arte" to "UCLLibJTCy3sXjHLVaDimnpQ",
        "Kino" to "UCOL10n-as9dXO2qtjjFUQbQ",           // KinoCheck: deutsche Kino- & Streaming-Trailer
        "Warner Bros." to "UCR88edMaCp9veFK6PVXyYpQ",
        "Universal" to "UCPqZp43m3Cg69JkvCvXh8Eg",
    )

    private val isTrailer = Regex("trailer|teaser", RegexOption.IGNORE_CASE)
    private val cutTrailer = Regex(
        "\\s*[–—\\-:]?\\s*\\(?\\s*(offizieller|offizielle|official|finaler|neuer|deutscher)?\\s*(teaser[- ]?)?(trailer|teaser).*$",
        RegexOption.IGNORE_CASE
    )

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")

    /** Aus „Frisco King S1 (Offizieller Trailer) | Paramount+ Deutschland“ wird „Frisco King“. */
    fun showName(title: String): String {
        var t = title.substringBefore(" | ").trim()
        t = t.replace(cutTrailer, "").trim()
        t = t.replace(Regex("\\s+(S\\d+|Staffel \\d+|Season \\d+)\\s*$", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s*[–—\\-:|]\\s*$"), "").trim()
        return t.ifBlank { title }
    }

    private suspend fun feed(provider: String, id: String): List<TrailerItem> = withContext(Dispatchers.IO) {
        val conn = URL("https://www.youtube.com/feeds/videos.xml?channel_id=$id").openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 15000
        try {
            if (conn.responseCode !in 200..299) return@withContext emptyList()
            val xml = conn.inputStream.bufferedReader().use { it.readText() }
            Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
                val e = m.groupValues[1]
                fun tag(name: String) = Regex("<$name[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(e)?.groupValues?.get(1)?.let(::unescape) ?: ""
                val vid = tag("yt:videoId")
                val title = tag("title")
                if (vid.isBlank() || !isTrailer.containsMatchIn(title)) return@mapNotNull null
                val published = runCatching { java.time.OffsetDateTime.parse(tag("published")).toEpochSecond() }.getOrDefault(0L)
                val thumb = Regex("<media:thumbnail url=\"([^\"]+)\"").find(e)?.groupValues?.get(1) ?: "https://i.ytimg.com/vi/$vid/hqdefault.jpg"
                TrailerItem(vid, title, showName(title), provider, published, thumb, tag("media:description").take(400))
            }.toList()
        } catch (e: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    /** Alle neuesten Trailer aller Kanäle, neueste zuerst. */
    suspend fun latest(): List<TrailerItem> = coroutineScope {
        val all = channels.map { (p, id) -> async { feed(p, id) } }.flatMap { it.await() }
        if (all.isEmpty()) throw IOException("YouTube gerade nicht erreichbar.")
        all.sortedByDescending { it.published }.distinctBy { it.videoId }
    }

    /** Passenden Trailer zu einem Titel finden. */
    fun match(list: List<TrailerItem>, title: String): TrailerItem? {
        val n = normName(title)
        if (n.length < 3) return null
        return list.firstOrNull { normName(it.show) == n } ?: list.firstOrNull { n.length >= 6 && normName(it.title).contains(n) }
    }
}

// ======================= DVD- & Blu-ray-Neuheiten =======================

data class DiscRelease(
    val title: String,
    val formats: List<String>,
    val weekStart: Long,     // Sekunden
    val source: String,
    val asin: String? = null,
) {
    /** Cover-Bild direkt von Amazon (über die Artikelnummer). */
    val cover get() = asin?.let { "https://m.media-amazon.com/images/P/$it.01._SCLZZZZZZZ_.jpg" }
    val key get() = "disc_" + normName(title)
}

/**
 * Wöchentliche DVD-/Blu-ray-Neuheiten laut AREA DVD (areadvd.de, „Neuheiten der Woche“).
 * Musik-Veröffentlichungen werden ausgelassen.
 */
object DiscReleases {
    private val editionWords = Regex(
        "\\s+-\\s+(steelbook|mediabook|fullsleeve|collector'?s edition|limited|limitierte|special edition|digipak|ultimate|deluxe|wattiertes|uncut|extended).*$",
        RegexOption.IGNORE_CASE
    )

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Filmabend-App")
        try {
            if (conn.responseCode !in 200..299) throw IOException("AREA DVD nicht erreichbar (${conn.responseCode})")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&#038;", "&").replace("&#8211;", "–")
        .replace("&#8217;", "’").replace("&#8220;", "“").replace("&#8222;", "„").replace("&quot;", "\"").replace("&#039;", "'")

    private fun parsePost(html: String, weekStart: Long, link: String): List<DiscRelease> {
        val start = html.indexOf("In den nächsten Tagen").takeIf { it >= 0 } ?: html.indexOf("In dieser Woche").takeIf { it >= 0 } ?: return emptyList()
        val endCandidates = listOf("<p>Musik", "weitere Highlights", "alle Bluray Disc").map { html.indexOf(it, start) }.filter { it > 0 }
        val end = endCandidates.minOrNull() ?: (start + 20000).coerceAtMost(html.length)
        val part = html.substring(start, end)
        val raw = Regex("<li>(.*?)</li>", RegexOption.DOT_MATCHES_ALL).findAll(part)
            .map { m ->
                val inner = m.groupValues[1]
                val asin = Regex("/dp/([A-Z0-9]{10})").find(inner)?.groupValues?.get(1)
                unescape(inner.replace(Regex("<[^>]*>"), "")).trim() to asin
            }
            .filter { it.first.isNotBlank() }
        val byTitle = LinkedHashMap<String, Pair<String, MutableSet<String>>>()
        val asins = HashMap<String, String>()
        for ((line, asin) in raw) {
            val fmt = Regex("\\[([^\\]]+)\\]").find(line)?.groupValues?.get(1) ?: ""
            if (fmt.contains("CD") || fmt.contains("LP")) continue
            var title = line.replace(Regex("\\s*[\\[(][^\\])]*(blu-ray|dvd)[^\\])]*[\\])]", RegexOption.IGNORE_CASE), "").trim()
            val edition = editionWords.find(title)?.value?.removePrefix(" - ")?.trim()
            title = title.replace(editionWords, "").trim().trimEnd('-', '–', ' ')
            if (title.length < 2) continue
            val f = byTitle.getOrPut(normName(title)) { title to mutableSetOf() }.second
            // Für das Cover lieber die normale Ausgabe (ohne Steelbook o. ä.) nehmen
            if (asin != null && (edition == null || !asins.containsKey(normName(title)))) asins[normName(title)] = asin
            when {
                fmt.contains("4K", true) -> f += "4K"
                fmt.contains("Blu-ray", true) -> f += "Blu-ray"
                fmt.contains("DVD", true) -> f += "DVD"
            }
            if (edition != null) f += edition.substringBefore(" ").replaceFirstChar { it.uppercase() }
        }
        return byTitle.values.map { (t, f) -> DiscRelease(t, f.sortedBy { listOf("4K", "Blu-ray", "DVD").indexOf(it).let { i -> if (i < 0) 9 else i } }, weekStart, link, asins[normName(t)]) }
    }

    /** Die letzten [weeks] Wochen, neueste zuerst. */
    suspend fun latest(weeks: Int = 8): List<DiscRelease> = coroutineScope {
        val rss = get("https://www.areadvd.de/?s=Neuheiten+der+Woche&feed=rss2")
        val items = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(rss).mapNotNull { m ->
            val e = m.groupValues[1]
            val title = Regex("<title>(.*?)</title>").find(e)?.groupValues?.get(1) ?: ""
            if (!title.contains("der Woche")) return@mapNotNull null
            val link = Regex("<link>(.*?)</link>").find(e)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
            val date = Regex("<pubDate>(.*?)</pubDate>").find(e)?.groupValues?.get(1)?.trim() ?: ""
            val ts = runCatching {
                java.time.ZonedDateTime.parse(date, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
            }.getOrDefault(0L)
            Triple(link, ts, title)
        }.toList().take(weeks)
        if (items.isEmpty()) throw IOException("Keine DVD-/Blu-ray-Neuheiten gefunden.")
        val pages = items.map { (link, ts, _) ->
            async { runCatching { parsePost(get(link), ts, link) }.getOrDefault(emptyList()) }
        }.map { it.await() }
        // Jeder Titel nur einmal (in der Woche, in der er zuerst auftaucht)
        pages.flatten().sortedByDescending { it.weekStart }.distinctBy { it.key }
    }
}

/** Kleiner Zwischenspeicher: passenden Katalog-Eintrag (Poster, Details) zu einem Titel finden. */
object TitleLookup {
    private val cache = HashMap<String, CatalogItem?>()
    private val lock = kotlinx.coroutines.sync.Mutex()

    suspend fun find(title: String): CatalogItem? {
        val k = normName(title)
        if (cache.containsKey(k)) return cache[k]
        return lock.withLockCompat {
            if (cache.containsKey(k)) return@withLockCompat cache[k]
            kotlinx.coroutines.delay(120)
            val res = runCatching {
                val hits = Catalog.search(title)
                hits.firstOrNull { normName(it.title) == k }
                    ?: hits.firstOrNull { val h = normName(it.title); k.length >= 5 && h.length >= 5 && (h.startsWith(k) || k.startsWith(h)) }
            }
            val hit = res.getOrNull()
            if (res.isSuccess) cache[k] = hit   // Fehler nicht merken – beim nächsten Mal neu versuchen
            hit
        }
    }

    private suspend fun <T> kotlinx.coroutines.sync.Mutex.withLockCompat(block: suspend () -> T): T {
        lock(); try { return block() } finally { unlock() }
    }
}

// ======================= Schauspieler-Suche =======================

data class Person(
    val id: Int,          // TVmaze-Nummer, 0 = unbekannt
    val name: String,
    val image: String?,
    val birthday: String,
    val country: String,
)

object People {
    private fun JSONObject.s(k: String) = if (isNull(k)) "" else optString(k, "")

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", "Filmabend-App (Android)")
        try {
            if (conn.responseCode !in 200..299) throw IOException("Nicht erreichbar (${conn.responseCode})")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Personen mit diesem Namen (TVmaze). */
    suspend fun search(q: String): List<Person> {
        val arr = JSONArray(get("https://api.tvmaze.com/search/people?q=${URLEncoder.encode(q, "UTF-8")}"))
        return (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONObject(i)?.optJSONObject("person") ?: return@mapNotNull null
            Person(
                id = p.optInt("id"),
                name = p.s("name"),
                image = p.optJSONObject("image")?.s("medium")?.ifBlank { null },
                birthday = p.s("birthday"),
                country = p.optJSONObject("country")?.s("name") ?: "",
            )
        }.filter { it.name.isNotBlank() }
    }

    /** Filme mit diesem Schauspieler (iTunes, Suche nach Darsteller). */
    suspend fun movies(name: String): List<CatalogItem> {
        val json = get("https://itunes.apple.com/search?term=${URLEncoder.encode(name, "UTF-8")}&country=de&media=movie&entity=movie&attribute=actorTerm&limit=100")
        val arr = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optLong("trackId").toInt().takeIf { it != 0 } ?: return@mapNotNull null
            val title = o.s("trackName").ifBlank { return@mapNotNull null }
            CatalogItem(
                id = id, type = "movie", title = title, year = o.s("releaseDate").take(4),
                poster = o.s("artworkUrl100").takeIf { it.isNotBlank() }
                    ?.replace(Regex("/\\d+x\\d+bb(-\\d+)?\\.(png|jpg|jpeg)$"), "/400x600bb.jpg"),
                overview = o.s("longDescription"), vote = 0.0,
                genres = listOf(o.s("primaryGenreName")).filter { it.isNotBlank() }.map { Catalog.genre(it) },
                trailer = o.s("previewUrl").ifBlank { null },
            )
        }.distinctBy { it.key }.sortedByDescending { it.year }
    }

    /** Serien, in denen die Person mitspielt (TVmaze). */
    suspend fun series(id: Int): List<CatalogItem> {
        if (id <= 0) return emptyList()
        val arr = JSONArray(get("https://api.tvmaze.com/people/$id/castcredits?embed=show"))
        return (0 until arr.length()).mapNotNull { i ->
            val show = arr.optJSONObject(i)?.optJSONObject("_embedded")?.optJSONObject("show") ?: return@mapNotNull null
            val sid = show.optInt("id").takeIf { it != 0 } ?: return@mapNotNull null
            val g = show.optJSONArray("genres")
            CatalogItem(
                id = sid, type = "tv", title = show.s("name"), year = show.s("premiered").take(4),
                poster = show.optJSONObject("image")?.s("medium")?.ifBlank { null },
                overview = show.s("summary").replace(Regex("<[^>]*>"), ""),
                vote = show.optJSONObject("rating")?.optDouble("average", 0.0)?.takeIf { !it.isNaN() } ?: 0.0,
                genres = if (g == null) emptyList() else (0 until g.length()).map { Catalog.genre(g.optString(it)) }.distinct(),
            )
        }.distinctBy { it.key }.sortedByDescending { it.year }
    }
}

// ======================= Deutsches Fernsehen (privat & öffentlich-rechtlich) =======================

data class TvShow(
    val item: CatalogItem,
    val network: String,
    val public: Boolean,
    val kind: String,        // Show, Reality-TV, Doku, Serie, Talk …
    val nextAir: Long,       // Sekunden
    val airings: Int,
)

/** Fernsehprogramm der nächsten Tage (TVmaze, Land DE) – zu Sendungen gruppiert. */
object GermanTv {
    val publicNetworks = setOf(
        "das erste", "ard", "zdf", "zdfneo", "zdfinfo", "3sat", "arte", "ard-alpha", "ndr", "ndr fernsehen", "wdr",
        "swr", "swr fernsehen", "br", "br fernsehen", "hr", "hr-fernsehen", "mdr", "rbb", "sr", "radio bremen",
        "one", "kika", "phoenix", "tagesschau24", "funk",
    )
    private val skipTypes = setOf("News", "Sports")

    private fun kindOf(type: String, genres: List<String>): String = when (type) {
        "Reality" -> "Reality-TV"
        "Game Show" -> "Quiz & Show"
        "Talk Show" -> "Talk"
        "Documentary" -> "Doku"
        "Variety" -> "Show"
        "Animation" -> "Animation"
        "Scripted" -> genres.firstOrNull() ?: "Serie"
        else -> genres.firstOrNull() ?: type.ifBlank { "Sendung" }
    }

    suspend fun upcoming(days: Int = 7): List<TvShow> = coroutineScope {
        val today = java.time.LocalDate.now()
        val zone = java.time.ZoneId.of("Europe/Berlin")
        val nowSec = System.currentTimeMillis() / 1000
        val shows = LinkedHashMap<Int, TvShow>()
        for (chunk in (0 until days).map { today.plusDays(it.toLong()) }.chunked(4)) {
            val arrays = chunk.map { d ->
                async { runCatching { JSONArray(fetchText("https://api.tvmaze.com/schedule?country=DE&date=$d")) }.getOrNull() }
            }.map { it.await() }
            for (arr in arrays) {
                if (arr == null) continue
                for (i in 0 until arr.length()) {
                    val ep = arr.optJSONObject(i) ?: continue
                    val show = ep.optJSONObject("show") ?: continue
                    val type = if (show.isNull("type")) "" else show.optString("type")
                    if (type in skipTypes) continue
                    val sid = show.optInt("id").takeIf { it != 0 } ?: continue
                    val net = show.optJSONObject("network")?.optString("name") ?: continue
                    val air = runCatching {
                        java.time.LocalDateTime.parse(ep.optString("airdate") + "T" + ep.optString("airtime").ifBlank { "00:00" })
                            .atZone(zone).toEpochSecond()
                    }.getOrDefault(0L)
                    if (air < nowSec - 3600) continue
                    val existing = shows[sid]
                    if (existing != null) {
                        shows[sid] = existing.copy(nextAir = minOf(existing.nextAir, air), airings = existing.airings + 1)
                        continue
                    }
                    val g = show.optJSONArray("genres")
                    val genres = if (g == null) emptyList() else (0 until g.length()).map { Catalog.genre(g.optString(it)) }.distinct()
                    val img = show.optJSONObject("image")
                    val item = CatalogItem(
                        id = sid, type = "tv",
                        title = show.optString("name"),
                        year = (if (show.isNull("premiered")) "" else show.optString("premiered")).take(4),
                        poster = img?.optString("medium")?.ifBlank { null },
                        overview = (if (show.isNull("summary")) "" else show.optString("summary")).replace(Regex("<[^>]*>"), "").trim(),
                        vote = show.optJSONObject("rating")?.optDouble("average", 0.0)?.takeIf { !it.isNaN() } ?: 0.0,
                        genres = (genres + kindOf(type, genres)).distinct(),
                        weight = show.optInt("weight"),
                    )
                    shows[sid] = TvShow(item, net, net.lowercase() in publicNetworks, kindOf(type, genres), air, 1)
                }
            }
            kotlinx.coroutines.delay(600)
        }
        if (shows.isEmpty()) throw IOException("Fernsehprogramm gerade nicht erreichbar.")
        shows.values.toList()
    }

    private suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", "Filmabend-App (Android)")
        try {
            if (conn.responseCode !in 200..299) throw IOException("Fehler ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}


// ======================= JustWatch (deutsche Titel, Poster, Genres, Streaming-Anbieter) =======================

/**
 * Öffentliche JustWatch-Schnittstelle (ohne Schlüssel). Liefert deutsche Titel & Beschreibungen,
 * Poster, Genres, IMDb-Wertung, YouTube-Trailer und bei welchem Anbieter etwas läuft.
 * JustWatch-Nummern werden um [OFFSET] verschoben, damit sie nicht mit iTunes/TVmaze kollidieren.
 */
object JW {
    const val OFFSET = 2_000_000_000
    fun isJw(id: Int) = id >= OFFSET

    /** Unsere Genres -> JustWatch-Kürzel */
    val genreCodes = linkedMapOf(
        "Komödie" to listOf("cmy"), "Thriller" to listOf("trl"), "Action" to listOf("act"), "Krimi" to listOf("crm"),
        "Drama" to listOf("drm"), "Horror" to listOf("hrr"), "Romantik" to listOf("rma"),
        "Sci-Fi & Fantasy" to listOf("scf", "fnt"), "Abenteuer" to listOf("act"), "Animation" to listOf("ani"),
        "Familie" to listOf("fml"), "Doku" to listOf("doc"), "Geschichte & Krieg" to listOf("hst", "war"),
        "Mystery" to listOf("trl"), "Reality-TV" to listOf("rly"), "Musik" to listOf("msc"), "Sport" to listOf("spt"),
        "Western" to listOf("wsn"),
    )

    private const val FIELDS = "objectId objectType content(country:\$country,language:\$language){title originalReleaseYear posterUrl shortDescription genres{translation(language:\$language)} scoring{imdbScore} clips{externalId provider}}"

    private suspend fun gql(query: String, vars: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        vars.put("country", "DE"); vars.put("language", "de")
        val body = JSONObject().put("query", query).put("variables", vars).toString()
        val conn = URL("https://apis.justwatch.com/graphql").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"; conn.doOutput = true
        conn.connectTimeout = 15000; conn.readTimeout = 20000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Filmabend-App")
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) throw IOException("JustWatch nicht erreichbar (${conn.responseCode})")
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            o.optJSONObject("data") ?: throw IOException("JustWatch: keine Daten")
        } finally {
            conn.disconnect()
        }
    }

    private fun JSONObject.s(k: String) = if (isNull(k)) "" else optString(k, "")

    fun parse(node: JSONObject): CatalogItem? {
        val oid = node.optInt("objectId").takeIf { it > 0 } ?: return null
        val type = if (node.s("objectType") == "SHOW") "tv" else "movie"
        val c = node.optJSONObject("content") ?: return null
        val title = c.s("title").ifBlank { return null }
        val poster = c.s("posterUrl").takeIf { it.isNotBlank() }
            ?.let { "https://images.justwatch.com" + it.replace("{profile}", "s332").replace("{format}", "jpg") }
        val g = c.optJSONArray("genres")
        val genres = if (g == null) emptyList() else (0 until g.length()).mapNotNull { g.optJSONObject(it)?.s("translation")?.ifBlank { null } }
            .map { Catalog.genre(it) }.distinct()
        val clips = c.optJSONArray("clips")
        val yt = if (clips == null) null else (0 until clips.length()).mapNotNull { clips.optJSONObject(it) }
            .firstOrNull { it.s("provider") == "YOUTUBE" }?.s("externalId")?.ifBlank { null }
        return CatalogItem(
            id = OFFSET + oid, type = type, title = title,
            year = c.optInt("originalReleaseYear").takeIf { it > 0 }?.toString() ?: "",
            poster = poster, overview = c.s("shortDescription"),
            vote = c.optJSONObject("scoring")?.optDouble("imdbScore", 0.0)?.takeIf { !it.isNaN() } ?: 0.0,
            genres = genres, yt = yt,
        )
    }

    private fun edges(data: JSONObject): List<CatalogItem> {
        val e = data.optJSONObject("popularTitles")?.optJSONArray("edges") ?: return emptyList()
        return (0 until e.length()).mapNotNull { e.optJSONObject(it)?.optJSONObject("node")?.let(::parse) }.distinctBy { it.key }
    }

    private const val POPULAR = "query(\$country:Country!,\$language:Language!,\$first:Int!,\$filter:TitleFilter){popularTitles(country:\$country,first:\$first,filter:\$filter){edges{node{$FIELDS}}}}"

    suspend fun search(q: String, first: Int = 30): List<CatalogItem> =
        edges(gql(POPULAR, JSONObject().put("first", first).put("filter", JSONObject().put("searchQuery", q))))

    /** Beliebte Titel, optional nach Typ ("movie"/"tv"), Genres, Erscheinungsjahr oder Person. */
    suspend fun popular(type: String? = null, genres: List<String> = emptyList(), first: Int = 40, minYear: Int? = null, personId: Int? = null): List<CatalogItem> {
        val f = JSONObject()
        if (type != null) f.put("objectTypes", JSONArray().put(if (type == "tv") "SHOW" else "MOVIE"))
        if (genres.isNotEmpty()) f.put("genres", JSONArray(genres))
        if (minYear != null) f.put("releaseYear", JSONObject().put("min", minYear))
        if (personId != null) f.put("personId", "tp$personId")
        return edges(gql(POPULAR, JSONObject().put("first", first).put("filter", f)))
    }

    /** Beliebte Titel eines unserer Genres (z. B. „Komödie“). */
    suspend fun byGenre(genre: String, type: String? = null, first: Int = 40): List<CatalogItem> {
        val codes = genreCodes[genre] ?: return emptyList()
        return popular(type, codes, first)
    }

    suspend fun detail(id: Int, type: String): CatalogDetail {
        val nodeId = (if (type == "tv") "ts" else "tm") + (id - OFFSET)
        val q = "query(\$id:ID!,\$country:Country!,\$language:Language!){node(id:\$id){ ... on MovieOrShow{$FIELDS offers(country:\$country,platform:WEB){monetizationType package{clearName}} content(country:\$country,language:\$language){runtime}} ... on Show{totalSeasonCount}}}"
        val node = gql(q, JSONObject().put("id", nodeId)).optJSONObject("node") ?: throw IOException("Titel nicht gefunden")
        val item = parse(node) ?: throw IOException("Titel nicht gefunden")
        val offers = node.optJSONArray("offers")
        val flat = if (offers == null) emptyList() else (0 until offers.length()).mapNotNull { offers.optJSONObject(it) }
            .filter { it.s("monetizationType") in setOf("FLATRATE", "FREE", "ADS") }
            .mapNotNull { it.optJSONObject("package")?.s("clearName")?.ifBlank { null } }
            .map { it.replace(" with Ads", "").replace(Regex(" Amazon Channel$"), " (Amazon Channel)") }
            .distinct()
        return CatalogDetail(
            item = item, genres = item.genres,
            seasons = node.optInt("totalSeasonCount"),
            runtime = node.optJSONObject("content")?.optInt("runtime") ?: 0,
            network = "", status = "", providers = flat,
        )
    }

    /** Alle Filme & Serien einer Person (über die Besetzungsliste gesucht). */
    suspend fun personWorks(name: String): List<CatalogItem> {
        val n = normName(name)
        val q = "query(\$country:Country!,\$language:Language!,\$first:Int!,\$filter:TitleFilter){popularTitles(country:\$country,first:\$first,filter:\$filter){edges{node{objectId content(country:\$country,language:\$language){credits{name personId}}}}}}"
        val data = gql(q, JSONObject().put("first", 10).put("filter", JSONObject().put("searchQuery", name)))
        val e = data.optJSONObject("popularTitles")?.optJSONArray("edges")
        var pid: Int? = null
        if (e != null) loop@ for (i in 0 until e.length()) {
            val credits = e.optJSONObject(i)?.optJSONObject("node")?.optJSONObject("content")?.optJSONArray("credits") ?: continue
            for (j in 0 until credits.length()) {
                val c = credits.optJSONObject(j) ?: continue
                if (normName(c.s("name")) == n) { pid = c.optInt("personId"); break@loop }
            }
        }
        return if (pid != null && pid > 0) popular(first = 100, personId = pid) else search(name, 40)
    }
}

// ======================= Fehlende Bilder suchen =======================

/**
 * Sucht ein Bild zu einem Titel, wenn keines da ist – nacheinander bei
 * JustWatch, TVmaze, den Mediatheken (Vorschaubild der Sendung) und Wikipedia.
 */
object PosterFinder {
    private fun JSONObject.s(k: String) = if (isNull(k)) "" else optString(k, "")

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000; conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Filmabend-App")
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) throw IOException("${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText().take(400_000) }
        } finally {
            conn.disconnect()
        }
    }

    private fun close(a: String, b: String): Boolean {
        val x = normName(a); val y = normName(b)
        if (x.isBlank() || y.isBlank()) return false
        return x == y || (x.length >= 5 && y.length >= 5 && (x.startsWith(y) || y.startsWith(x)))
    }

    private suspend fun fromJustWatch(title: String): String? =
        runCatching { JW.search(title, 8).firstOrNull { close(it.title, title) }?.poster }.getOrNull()

    private suspend fun fromTvmaze(title: String): String? = runCatching {
        val o = JSONObject(get("https://api.tvmaze.com/singlesearch/shows?q=" + URLEncoder.encode(title, "UTF-8")))
        if (!close(o.s("name"), title)) null
        else o.optJSONObject("image")?.let { it.s("original").ifBlank { it.s("medium") } }?.ifBlank { null }
    }.getOrNull()

    /** Vorschaubild (og:image) der Sendungsseite in ARD/ZDF/arte & Co. */
    private suspend fun fromMediathek(title: String): String? = runCatching {
        val body = JSONObject().put("queries", JSONArray().put(JSONObject().put("fields", JSONArray().put("topic").put("title")).put("query", title)))
            .put("sortBy", "timestamp").put("sortOrder", "desc").put("future", false).put("offset", 0).put("size", 5).toString()
        val json = withContext(Dispatchers.IO) {
            val conn = URL("https://mediathekviewweb.de/api/query").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.connectTimeout = 12000; conn.readTimeout = 15000
            conn.setRequestProperty("Content-Type", "text/plain")
            try {
                conn.outputStream.use { it.write(body.toByteArray()) }
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally { conn.disconnect() }
        }
        val arr = JSONObject(json).optJSONObject("result")?.optJSONArray("results") ?: return@runCatching null
        for (i in 0 until arr.length()) {
            val site = arr.optJSONObject(i)?.s("url_website") ?: continue
            if (site.isBlank()) continue
            val html = runCatching { get(site) }.getOrNull() ?: continue
            val img = Regex("<meta[^>]+property=\"og:image\"[^>]+content=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                ?: Regex("<meta[^>]+content=\"([^\"]+)\"[^>]+property=\"og:image\"").find(html)?.groupValues?.get(1)
            if (!img.isNullOrBlank()) return@runCatching img.replace("&amp;", "&")
        }
        null
    }.getOrNull()

    /** Hauptbild des passenden Wikipedia-Artikels (erst Deutsch, dann Englisch). */
    private suspend fun fromWikipedia(title: String, lang: String): String? = runCatching {
        val url = "https://$lang.wikipedia.org/w/api.php?action=query&format=json&generator=search&gsrlimit=3&prop=pageimages&piprop=thumbnail&pithumbsize=500&gsrsearch=" +
            URLEncoder.encode(title, "UTF-8")
        val pages = JSONObject(get(url)).optJSONObject("query")?.optJSONObject("pages") ?: return@runCatching null
        val list = pages.keys().asSequence().mapNotNull { pages.optJSONObject(it) }
            .sortedBy { it.optInt("index", 99) }.toList()
        list.firstOrNull { close(it.s("title").substringBefore(" ("), title) && it.optJSONObject("thumbnail") != null }
            ?.optJSONObject("thumbnail")?.s("source")?.ifBlank { null }
    }.getOrNull()

    suspend fun find(title: String, type: String): String? {
        val clean = title.substringBefore(" – ").substringBefore(" - ").trim().ifBlank { title }
        return fromJustWatch(title)
            ?: (if (type == "tv") fromTvmaze(title) else null)
            ?: fromMediathek(title)
            ?: (if (clean != title) fromJustWatch(clean) else null)
            ?: fromWikipedia(title, "de")
            ?: fromWikipedia(clean, "de")
            ?: fromWikipedia(title, "en")
            ?: fromTvmaze(clean)
    }
}
