package de.eq.app

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.math.pow
import kotlin.math.sqrt

/** Ergebnis eines eingelesenen parametrischen EQ (AutoEq / Equalizer APO). */
data class ParsedEq(val preamp: Float, val filters: List<PFilter>)

/**
 * Zugriff auf die AutoEq-Datenbank (github.com/jaakkopasanen/AutoEq):
 * Korrekturkurven für tausende Kopfhörer, als parametrischer EQ.
 */
object AutoEq {
    private const val BASE = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/"
    private const val INDEX_URL = BASE + "INDEX.md"
    private const val CACHE_DAYS = 14L

    /** Ein Kopfhörer im Index: Name, Messquelle und Ordner (relativ zu results/). */
    data class Entry(val name: String, val source: String, val path: String)

    private val indexLine = Regex("""^- \[(.+)]\(\./(.+)\) by (.+)$""")

    fun parseIndex(md: String): List<Entry> {
        val list = md.lineSequence().mapNotNull { line ->
            val m = indexLine.find(line.trim()) ?: return@mapNotNull null
            Entry(m.groupValues[1], m.groupValues[3], decode(m.groupValues[2]))
        }.toList()
        // Bevorzugte Messquellen zuerst (oratory1990 und crinacle gelten als besonders genau)
        return list.sortedWith(compareBy({ it.name.lowercase() }, { rank(it.source) }))
    }

    private fun rank(source: String) = when {
        source.startsWith("oratory1990") -> 0
        source.startsWith("crinacle") -> 1
        else -> 2
    }

    /** Adresse der „ParametricEQ.txt“ eines Kopfhörers. */
    fun parametricUrl(e: Entry): String {
        val folder = e.path.substringAfterLast('/')
        return BASE + e.path.split('/').joinToString("/") { encode(it) } + "/" + encode("$folder ParametricEQ.txt")
    }

    /** Index laden – aus dem Zwischenspeicher, wenn er jünger als 14 Tage ist. */
    fun loadIndex(context: Context): List<Entry> {
        val cache = File(context.filesDir, "autoeq_index.md")
        val fresh = cache.exists() &&
            System.currentTimeMillis() - cache.lastModified() < CACHE_DAYS * 24 * 3600 * 1000
        if (!fresh) {
            try {
                cache.writeText(download(INDEX_URL))
            } catch (e: IOException) {
                if (!cache.exists()) throw e   // ohne Netz: alten Stand verwenden, falls vorhanden
            }
        }
        return parseIndex(cache.readText())
    }

    fun download(url: String): String {
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.connectTimeout = 15000
            con.readTimeout = 30000
            con.setRequestProperty("User-Agent", "EqualizerApp")
            if (con.responseCode != 200) throw IOException("Server antwortet mit ${con.responseCode}")
            return con.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            con.disconnect()
        }
    }

    private val preampRe = Regex("""^Preamp\s*:\s*([-+]?[\d.]+)\s*dB""", RegexOption.IGNORE_CASE)
    private val channelRe = Regex("""^Channel\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val filterRe = Regex(
        """^Filter\s*\d*\s*:\s*(ON|OFF)\s+([A-Z]+)(?:\s+\d+\s*dB)?\s+Fc\s+([\d.]+)\s*Hz""" +
        """(?:\s+Gain\s+([-+]?[\d.]+)\s*dB)?(?:\s+Q\s+([\d.]+))?(?:\s+BW\s+Oct\s+([\d.]+))?""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Liest das Equalizer-APO-Format, das AutoEq verwendet:
     *   Preamp: -5.3 dB
     *   Channel: L                     (folgende Filter nur links; R = rechts, ALL = beide)
     *   Filter 1: ON LSC Fc 105 Hz Gain 13.6 dB Q 0.70
     *   Filter 2: ON PK Fc 100 Hz Gain 1.0 dB BW Oct 0.167
     * Kommazahlen (-2,3) werden verstanden, alles hinter # ist Kommentar,
     * mehrere Preamp-Zeilen werden addiert (wie in Equalizer APO).
     */
    fun parseParametric(text: String): ParsedEq? {
        var preamp = 0f
        var channel = CH_BOTH
        val filters = ArrayList<PFilter>()
        for (raw in text.lines()) {
            val line = raw.substringBefore('#').trim()
                .replace(Regex("""(\d),(\d)"""), "$1.$2")
            if (line.isEmpty()) continue
            val pre = preampRe.find(line)
            if (pre != null) {
                pre.groupValues[1].toFloatOrNull()?.let { preamp += it }
                continue
            }
            val ch = channelRe.find(line)
            if (ch != null) {
                val v = ch.groupValues[1].uppercase().split(Regex("""[\s,]+""")).filter { it.isNotEmpty() }.toSet()
                channel = when {
                    v.contains("ALL") || (("L" in v || "1" in v) && ("R" in v || "2" in v)) -> CH_BOTH
                    "L" in v || "1" in v -> CH_LEFT
                    "R" in v || "2" in v -> CH_RIGHT
                    else -> CH_BOTH
                }
                continue
            }
            val m = filterRe.find(line) ?: continue
            val type = when (m.groupValues[2].uppercase()) {
                "PK", "PEQ", "PEAK", "MODAL" -> FilterType.PEAK
                "LS", "LSC", "LSQ" -> FilterType.LOW_SHELF
                "HS", "HSC", "HSQ" -> FilterType.HIGH_SHELF
                "LP", "LPQ" -> FilterType.LOW_PASS
                "HP", "HPQ" -> FilterType.HIGH_PASS
                else -> continue
            }
            val freq = m.groupValues[3].toFloatOrNull() ?: continue
            val gain = m.groupValues[4].toFloatOrNull()
            // Glocke und Shelf ohne Verstärkungsangabe wären wirkungslos → Zeile überspringen
            if (gain == null && FilterType.hasGain(type)) continue
            val bw = m.groupValues[6].toFloatOrNull()
            val q = m.groupValues[5].toFloatOrNull()
                ?: bw?.takeIf { it > 0f }?.let { val p = 2.0.pow(it.toDouble()); (sqrt(p) / (p - 1)).toFloat() }
                ?: 0.707f
            filters += PFilter(
                type = type,
                freq = freq.coerceIn(20f, 20000f),
                gain = (gain ?: 0f).coerceIn(-30f, 30f),
                q = q.coerceIn(0.1f, 10f),
                on = m.groupValues[1].equals("ON", ignoreCase = true),
                channel = channel,
            )
        }
        if (filters.isEmpty()) return null
        return ParsedEq(preamp.coerceIn(-20f, 10f), filters.take(20))
    }

    private fun decode(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
