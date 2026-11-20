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
) {
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

    /** Sucht Filme und Serien gleichzeitig und mischt die Treffer. */
    suspend fun search(q: String): List<CatalogItem> = coroutineScope {
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

    suspend fun details(type: String, id: Int): CatalogDetail =
        if (type == "tv") showDetail(id) else movieDetail(id)

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
