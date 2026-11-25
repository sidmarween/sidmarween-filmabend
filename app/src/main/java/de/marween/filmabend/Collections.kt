package de.marween.filmabend

import com.google.firebase.firestore.DocumentSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Ein Film innerhalb einer Sammlung (chronologisch). */
data class CollItem(
    val wd: String,          // Wikidata-ID, z. B. Q123
    val title: String,
    val year: String,
    val date: String,        // yyyy-MM-dd zum Sortieren
    val key: String = "",    // Bibliotheks-Schlüssel, sobald einmal abgehakt
    val lead: String = "",   // Darsteller der Hauptfigur, z. B. „Sean Connery“
    val director: String = "",
    val note: String = "",   // Besonderheit, z. B. „ohne James Bond“
) {
    fun toMap() = mapOf("wd" to wd, "title" to title, "year" to year, "date" to date, "key" to key,
        "lead" to lead, "director" to director, "note" to note)

    companion object {
        fun from(m: Map<*, *>) = CollItem(
            m["wd"] as? String ?: "", m["title"] as? String ?: "", m["year"] as? String ?: "",
            m["date"] as? String ?: "", m["key"] as? String ?: "",
            m["lead"] as? String ?: "", m["director"] as? String ?: "", m["note"] as? String ?: ""
        )
    }
}

/** Eine Sammlung, z. B. „James Bond“ oder „Disney-Meisterwerke“ – für beide sichtbar. */
data class Coll(
    val id: String,
    val name: String,
    val source: String,      // Wikidata-ID der Reihe / des Studios
    val items: List<CollItem>,
    val createdAt: Long,
    val role: String = "",       // durchgehende Hauptfigur, z. B. „James Bond“
    val version: Int = 1,
) {
    companion object {
        fun from(d: DocumentSnapshot): Coll? {
            val name = d.getString("name") ?: return null
            val items = (d.get("items") as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let(CollItem::from) } ?: emptyList()
            return Coll(d.id, name, d.getString("source") ?: "", items.sortedBy { it.date }, d.getLong("createdAt") ?: 0,
                d.getString("role") ?: "", (d.getLong("version") ?: 1).toInt())
        }
    }
}

/** Treffer bei der Suche nach einer Reihe / einem Studio. */
data class WdHit(val id: String, val label: String, val description: String)

/**
 * Wikidata (frei, ohne Schlüssel): Filmreihen, Franchises und Studios mit allen Filmen
 * und Erscheinungsdaten – daraus entstehen die chronologischen Sammlungen.
 */
object Wikidata {
    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 60000
        c.setRequestProperty("User-Agent", "Filmabend-App/1.1 (Android; private Film-Liste)")
        c.setRequestProperty("Accept", "application/sparql-results+json, application/json")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("Wikidata antwortet nicht (${c.responseCode})")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    suspend fun search(q: String): List<WdHit> {
        val url = "https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json&type=item&limit=12" +
            "&language=de&uselang=de&search=" + URLEncoder.encode(q, "UTF-8")
        val arr = JSONObject(get(url)).optJSONArray("search") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            WdHit(it.optString("id"), it.optString("label"), it.optString("description"))
        }
    }

    /** Ergebnis: Filme (chronologisch) + die durchgehende Hauptfigur der Reihe (falls es eine gibt). */
    data class Result(val items: List<CollItem>, val role: String)

    suspend fun collection(source: String): Result {
        val list = films(source)
        if (list.isEmpty()) return Result(list, "")
        return runCatching { withDetails(list) }.getOrElse { Result(list, "") }
    }

    private fun sparql(q: String) = "https://query.wikidata.org/sparql?format=json&query=" + URLEncoder.encode(q, "UTF-8")

    /**
     * Regie und Rollen nachladen: Welche Figur zieht sich durch die Reihe (z. B. James Bond)
     * und wer spielt sie in welchem Film? Filme ohne diese Figur bekommen einen Hinweis.
     */
    private suspend fun withDetails(list: List<CollItem>): Result {
        val directors = HashMap<String, MutableList<String>>()
        val roles = HashMap<String, MutableList<Pair<String, String>>>() // film -> (Figur, Darsteller)
        for (chunk in list.chunked(120)) {
            val values = chunk.joinToString(" ") { "wd:${it.wd}" }
            val q = """
                SELECT ?film ?kind ?p ?who ?char ?charLabel WHERE {
                  VALUES ?film { $values }
                  {
                    BIND("d" AS ?kind)
                    ?film wdt:P57 ?p .
                    ?p rdfs:label ?who FILTER(LANG(?who) IN ("de", "en"))
                  } UNION {
                    BIND("r" AS ?kind)
                    ?film p:P161 ?st . ?st ps:P161 ?p ; pq:P453 ?char .
                    ?p rdfs:label ?who FILTER(LANG(?who) IN ("de", "en"))
                    ?char rdfs:label ?charLabel FILTER(LANG(?charLabel) = "de" || LANG(?charLabel) = "en")
                  }
                }
            """.trimIndent()
            val rows = JSONObject(get(sparql(q))).getJSONObject("results").getJSONArray("bindings")
            // Namen: Deutsch bevorzugt, sonst Englisch
            val names = HashMap<String, String>()
            fun name(uri: String, label: String, lang: String) {
                if (uri.isBlank() || label.isBlank()) return
                if (lang == "de" || !names.containsKey(uri)) names[uri] = label
            }
            val raw = ArrayList<Triple<String, String, Pair<String, String>>>() // kind, film, (person, char)
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                fun v(k: String) = r.optJSONObject(k)?.optString("value") ?: ""
                fun lang(k: String) = r.optJSONObject(k)?.optString("xml:lang") ?: ""
                name(v("p"), v("who"), lang("who"))
                name(v("char"), v("charLabel"), lang("charLabel"))
                raw += Triple(v("kind"), v("film").substringAfterLast('/'), v("p") to v("char"))
            }
            for ((kind, film, pc) in raw.distinct()) {
                val who = names[pc.first] ?: continue
                when (kind) {
                    "d" -> directors.getOrPut(film) { mutableListOf() }.let { if (who !in it) it += who }
                    "r" -> {
                        val ch = names[pc.second] ?: continue
                        val l = roles.getOrPut(film) { mutableListOf() }
                        if ((ch to who) !in l) l += ch to who
                    }
                }
            }
        }
        // Durchgehende Hauptfigur: kommt in den meisten Filmen vor (mind. 3 und mind. 40 %)
        val charCount = roles.values.flatMap { l -> l.map { it.first }.distinct() }.groupingBy { it }.eachCount()
        val main = charCount.entries.filter { it.value >= 3 && it.value >= list.size * 0.4 }.maxByOrNull { it.value }?.key ?: ""
        // Lohnt sich nur, wenn die Figur von mehreren Darstellern gespielt wird
        val actors = if (main.isBlank()) emptySet() else
            roles.values.mapNotNull { l -> l.firstOrNull { it.first == main }?.second }.toSet()
        val role = if (actors.size >= 2) main else ""
        val items = list.map { f ->
            val d = directors[f.wd].orEmpty().distinct().take(2).joinToString(", ")
            val lead = if (role.isBlank()) "" else roles[f.wd]?.filter { it.first == role }?.map { it.second }?.distinct()?.joinToString(" / ") ?: ""
            val note = when {
                role.isNotBlank() && lead.isBlank() && roles.containsKey(f.wd) -> "Ohne $role"
                else -> ""
            }
            f.copy(director = d, lead = lead, note = note)
        }
        return Result(items, role)
    }

    /** Alle Filme einer Reihe / eines Franchise / eines Studios, nach Erscheinungsdatum. */
    suspend fun films(source: String): List<CollItem> {
        val q = """
            SELECT ?film (MIN(?date) AS ?d) (SAMPLE(?de) AS ?lde) (SAMPLE(?en) AS ?len) WHERE {
              { ?film wdt:P179 wd:$source } UNION { ?film wdt:P8345 wd:$source } UNION
              { ?film wdt:P272 wd:$source } UNION { ?film wdt:P361 wd:$source } UNION { ?film wdt:P750 wd:$source }
              ?film wdt:P31/wdt:P279?/wdt:P279? wd:Q11424 .
              OPTIONAL { ?film wdt:P577 ?date }
              OPTIONAL { ?film rdfs:label ?de FILTER(LANG(?de) = "de") }
              OPTIONAL { ?film rdfs:label ?en FILTER(LANG(?en) = "en") }
            } GROUP BY ?film ORDER BY ?d LIMIT 400
        """.trimIndent()
        val url = "https://query.wikidata.org/sparql?format=json&query=" + URLEncoder.encode(q, "UTF-8")
        val rows = JSONObject(get(url)).getJSONObject("results").getJSONArray("bindings")
        return (0 until rows.length()).map { rows.getJSONObject(it) }.mapNotNull { r ->
            fun v(k: String) = r.optJSONObject(k)?.optString("value") ?: ""
            val title = v("lde").ifBlank { v("len") }
            if (title.isBlank() || title.matches(Regex("Q\\d+"))) return@mapNotNull null
            val date = v("d").take(10)
            CollItem(v("film").substringAfterLast('/'), title, date.take(4), date.ifBlank { "9999" })
        }.distinctBy { it.wd }.sortedBy { it.date }
    }
}

/**
 * Liest eine Filmliste aus einer Webseite (z. B. „Alle Disney-Filme in chronologischer
 * Reihenfolge“) oder aus eingefügtem Text. Erkennt Zeilen wie
 * „1. Schneewittchen und die sieben Zwerge (1937)“, „Bambi – 1942“ oder „1940: Pinocchio“.
 */
object ListImporter {
    /** Eine Webseite kann mehrere Listen enthalten (z. B. Zeichentrick, Realverfilmungen …). */
    data class Section(val title: String, val items: List<CollItem>)
    data class Parsed(val name: String, val sections: List<Section>) {
        val items get() = sections.flatMap { it.items }
    }

    private val yearRx = "(?:18[89]\\d|19\\d{2}|20[0-3]\\d)"
    private val patterns = listOf(
        // Titel (1937)  /  Titel [1937]  /  Titel (USA, 1937)
        Regex("^(.{2,110}?)\\s*[(\\[][^()\\[\\]]{0,30}?($yearRx)[^()\\[\\]]{0,12}[)\\]]"),
        // 1937: Titel  /  1937 – Titel
        Regex("^($yearRx)\\s*[:–—\\-|]\\s*(.{2,110})$"),
        // Titel – 1937  /  Titel, 1937  /  Titel | 1937
        Regex("^(.{2,110}?)\\s*[–—\\-,|:]\\s*($yearRx)\\b"),
    )
    private val numbered = Regex("^\\s*(?:#|Nr\\.?\\s*)?\\d{1,3}\\s*[.):]\\s+(.{2,110})$")

    suspend fun fromUrl(url: String): Parsed = withContext(Dispatchers.IO) {
        val c = URL(url.trim()).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36")
        c.setRequestProperty("Accept-Language", "de-DE,de;q=0.9")
        val html = try {
            if (c.responseCode !in 200..299) throw java.io.IOException("Webseite antwortet nicht (${c.responseCode})")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
        fromHtml(html)
    }

    fun fromHtml(raw: String): Parsed {
        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(raw)?.groupValues?.get(1)?.let { text(it) }?.substringBefore(" | ")?.substringBefore(" - ")?.trim() ?: ""
        val html = raw.replace(Regex("<(script|style|noscript|svg|nav|footer|header)[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        // Inhalt der typischen Listen-Bausteine (Überschriften, Aufzählungen, Tabellen, Absätze).
        // Überschriften ohne Film darin beginnen eine neue Teil-Liste.
        val sections = ArrayList<Pair<String, MutableList<String>>>()
        var current = "" to mutableListOf<String>()
        sections += current
        Regex("<(h[1-6]|li|td|th|p|dt|dd|strong|b)\\b[^>]*>(.*?)</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(html).forEach { m ->
                val tag = m.groupValues[1].lowercase()
                val txt = text(m.groupValues[2])
                if (tag.startsWith("h") && tag.length == 2 && parseLines(listOf(txt), false).isEmpty()) {
                    current = txt.take(80) to mutableListOf()
                    sections += current
                    return@forEach
                }
                txt.split('\n').map { it.trim() }.filter { it.length in 2..140 }.forEach { current.second += it }
            }
        val parsed = sections.map { (h, lines) -> Section(h, parseLines(lines, allowNoYear = false)) }
            .filter { it.items.size >= 3 }
            .mapIndexed { i, sec -> sec.copy(items = sec.items.map { it.copy(wd = "s${i}_" + it.wd) }) }
        return Parsed(title, parsed)
    }

    fun fromText(text: String): Parsed =
        Parsed("", listOf(Section("", parseLines(text.lines().map { it.trim() }.filter { it.isNotBlank() }, allowNoYear = true))).filter { it.items.isNotEmpty() })

    private fun text(htmlPart: String): String =
        android.text.Html.fromHtml(htmlPart.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n"), android.text.Html.FROM_HTML_MODE_LEGACY)
            .toString().replace(' ', ' ').replace(Regex("[ \\t]+"), " ").trim()

    private fun clean(t: String): String = t
        .replace(Regex("^\\s*(?:#|Nr\\.?\\s*)?\\d{1,3}\\s*[.):]\\s+"), "")   // Nummerierung
        .replace(Regex("^[•·*\\-–—]\\s*"), "")
        .trim().trim('„', '“', '"', '”', '\'', '»', '«', ':', '–', '-', ',', ' ')

    private fun parseLines(lines: List<String>, allowNoYear: Boolean): List<CollItem> {
        val out = LinkedHashMap<String, CollItem>()
        val noYear = ArrayList<String>()
        for (line in lines) {
            val l = clean(line)
            var title: String? = null; var year = ""
            for ((i, rx) in patterns.withIndex()) {
                val m = rx.find(l) ?: continue
                if (i == 1) { year = m.groupValues[1]; title = m.groupValues[2] } else { title = m.groupValues[1]; year = m.groupValues[2] }
                break
            }
            if (title == null) {
                // Nummerierte Zeile ohne Jahr („12. Bambi“) – nur merken
                numbered.find(line)?.let { noYear += clean(it.groupValues[1]) }
                if (allowNoYear && l.length in 2..80 && !l.endsWith(".")) noYear += l
                continue
            }
            val t = clean(title)
            if (t.length < 2 || t.split(' ').size > 14) continue
            val k = normName(t)
            if (k.isEmpty() || out.containsKey(k)) continue
            out[k] = CollItem("m" + k.hashCode().toUInt().toString(36), t, year, year.ifBlank { "9999" })
        }
        // Keine Jahreszahlen gefunden? Dann die nummerierte bzw. eingefügte Liste nehmen
        if (out.size < 3 && noYear.size >= 3) {
            out.clear()
            noYear.forEachIndexed { i, t ->
                val k = normName(t)
                if (k.isNotEmpty() && !out.containsKey(k))
                    out[k] = CollItem("m" + k.hashCode().toUInt().toString(36), t, "", "%04d".format(i))
            }
        }
        return out.values.toList()
    }
}
