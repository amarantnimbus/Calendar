package org.fossify.calendar.helpers

import android.content.Context
import org.fossify.calendar.extensions.config
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Talks to the FacturaScripts API (only when the "work features" switch is on and the address and
 * key are filled in Settings). Address and key live only in the phone settings.
 *
 * All the functions here block, so call them from a background thread.
 */
object FacturaScripts {
    data class Project(
        val id: Long,
        val name: String,
        val code: String = "",
        val client: String = "",
        val address: String = "",
        val municipality: String = ""
    ) {
        /** Short text kept in the event marker and shown in the project row. */
        fun label(): String = listOf(code, client).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { name }

        /** One line of the search results: code · client · address, municipality. */
        fun resultLine(): String {
            val place = listOf(address, municipality).filter { it.isNotBlank() }.joinToString(", ")
            return listOf(code, client, place).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { name }
        }
    }

    /** The extra fields of a project log entry (interlocutor, answer date, task...). */
    data class Extras(
        val tipoint: String = "",
        val interl: String = "",
        val sentido: String = "",
        val fresp: String = "",
        val dias: String = "",
        val estado: String = "",
        val completar: Boolean = false,
        val urgente: Boolean = false,
        val freg: String = "",
        val fecha: String = "",
        val tk: Boolean = false,
        // only sent when the entry is created, never kept
        val tareaTitulo: String = "",
        val tareaDesc: String = ""
    )

    data class Entry(
        val uid: String,
        val projectId: Long,
        val date: String,
        val title: String,
        val text: String,
        val type: String = "altres",
        val extras: Extras? = null
    )

    val INTERLOCUTOR_TYPES = listOf(
        "administracio" to org.fossify.calendar.R.string.fs_int_administracio,
        "client" to org.fossify.calendar.R.string.fs_int_client,
        "tecnic" to org.fossify.calendar.R.string.fs_int_tecnic,
        "constructor" to org.fossify.calendar.R.string.fs_int_constructor,
        "gestoria" to org.fossify.calendar.R.string.fs_int_gestoria,
        "notaria" to org.fossify.calendar.R.string.fs_int_notaria,
        "topograf" to org.fossify.calendar.R.string.fs_int_topograf,
        "altres" to org.fossify.calendar.R.string.fs_int_altres
    )

    /** The kinds of diary entry, in the order they are offered. The key is what is stored. */
    val TYPES = listOf(
        "client" to org.fossify.calendar.R.string.fs_type_client,
        "ajuntament" to org.fossify.calendar.R.string.fs_type_ajuntament,
        "obra" to org.fossify.calendar.R.string.fs_type_obra,
        "coaib" to org.fossify.calendar.R.string.fs_type_coaib,
        "admin" to org.fossify.calendar.R.string.fs_type_admin,
        "tecnics" to org.fossify.calendar.R.string.fs_type_tecnics,
        "altres" to org.fossify.calendar.R.string.fs_type_altres
    )

    fun typeLabelRes(key: String?): Int? = TYPES.firstOrNull { it.first == key }?.second

    private val sendLock = Any()

    private const val MARKER_START = "[[FS:"
    private const val MARKER_END = "]]"

    fun isConfigured(context: Context): Boolean {
        val config = context.config
        return config.workFeatures && config.fsUrl.isNotBlank() && config.fsApiKey.isNotBlank()
    }

    // ---- project marker kept at the end of the event description ----

    fun withMarker(text: String, project: Project?, type: String? = null, extras: Extras? = null): String {
        val clean = stripExtras(stripMarker(text)).trimEnd()
        return if (project == null) {
            clean
        } else {
            val name = project.label().replace("]", ")").replace("|", "/")
            val typePart = if (type.isNullOrEmpty()) "" else "|$type"
            val extraPart = if (extras == null) "" else "\n" + extrasMarker(extras)
            "$clean$extraPart\n$MARKER_START${project.id}|$name$typePart$MARKER_END"
        }
    }

    // ---- the extra fields travel in a second hidden marker, just before the project one ----

    private val extrasRegex = Regex("\\n?\\[\\[FX:([^\\]]*)\\]\\]")

    fun stripExtras(text: String): String = extrasRegex.replace(text, "")

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    private fun extrasMarker(e: Extras): String {
        val map = linkedMapOf(
            "ti" to e.tipoint, "in" to e.interl, "se" to e.sentido, "fr" to e.fresp, "dh" to e.dias,
            "es" to e.estado, "co" to if (e.completar) "1" else "", "ur" to if (e.urgente) "1" else "",
            "rg" to e.freg, "fe" to e.fecha, "tk" to if (e.tk || e.tareaTitulo.isNotBlank()) "1" else ""
        )
        return "[[FX:" + map.filter { it.value.isNotEmpty() }.entries.joinToString("&") { it.key + "=" + enc(it.value) } + "]]"
    }

    fun markerExtras(description: String): Extras? {
        val m = extrasRegex.find(description) ?: return null
        val map = HashMap<String, String>()
        m.groupValues[1].split('&').forEach {
            val kv = it.split('=', limit = 2)
            if (kv.size == 2) map[kv[0]] = java.net.URLDecoder.decode(kv[1], "UTF-8")
        }
        return Extras(
            tipoint = map["ti"] ?: "", interl = map["in"] ?: "", sentido = map["se"] ?: "",
            fresp = map["fr"] ?: "", dias = map["dh"] ?: "", estado = map["es"] ?: "",
            completar = map["co"] == "1", urgente = map["ur"] == "1",
            freg = map["rg"] ?: "", fecha = map["fe"] ?: "", tk = map["tk"] == "1"
        )
    }

    fun stripMarker(description: String): String {
        val start = description.lastIndexOf(MARKER_START)
        return if (start >= 0 && description.trimEnd().endsWith(MARKER_END)) {
            description.substring(0, start).trimEnd()
        } else {
            description
        }
    }

    private fun markerParts(description: String): List<String>? {
        val start = description.lastIndexOf(MARKER_START)
        if (start < 0 || !description.trimEnd().endsWith(MARKER_END)) {
            return null
        }

        return description.trimEnd()
            .removeSuffix(MARKER_END)
            .substring(start + MARKER_START.length)
            .split('|')
    }

    fun markerProject(description: String): Project? {
        val parts = markerParts(description) ?: return null
        val id = parts[0].toLongOrNull() ?: return null
        val name = if (parts.size >= 2) parts[1] else id.toString()
        return Project(id, name)
    }

    /** The type kept in the marker, or null when the event has none (older events). */
    fun markerType(description: String): String? {
        val parts = markerParts(description) ?: return null
        return if (parts.size >= 3 && TYPES.any { it.first == parts[2] }) parts[2] else null
    }

    // ---- HTTP ----

    private fun baseUrl(context: Context): String {
        var url = context.config.fsUrl.trim().trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        return url
    }

    private fun request(context: Context, method: String, path: String, form: Map<String, String>? = null): Pair<Int, String> {
        val connection = URL(baseUrl(context) + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 8000
            connection.readTimeout = 15000
            connection.setRequestProperty("Token", context.config.fsApiKey.trim())
            if (form != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val body = form.entries.joinToString("&") {
                    URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
                }
                connection.outputStream.use { it.write(body.toByteArray()) }
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Pair(code, text)
        } finally {
            connection.disconnect()
        }
    }

    // ---- projects ----

    /** Returns the projects, or null (with the reason in [lastError]) when it was not possible. */
    @Volatile
    var lastError = ""

    private val clientKeys = listOf("nombrecliente", "cliente", "nombre_cliente", "clientenombre", "razonsocial", "pa_cliente", "pa_nombrecliente")

    private fun fetchClientNames(context: Context): Map<String, String> {
        return try {
            val (code, body) = request(context, "GET", "/api/3/clientes?limit=5000")
            if (code !in 200..299) return emptyMap()
            val array = JSONArray(body)
            val map = HashMap<String, String>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val cod = item.optString("codcliente", "")
                val name = item.optString("nombre", "").ifEmpty { item.optString("razonsocial", "") }
                if (cod.isNotEmpty() && name.isNotEmpty()) map[cod] = name
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun fetchProjects(context: Context): List<Project>? {
        return try {
            val (code, body) = request(context, "GET", "/api/3/proyectos?limit=5000")
            if (code !in 200..299) {
                lastError = "HTTP $code"
                return null
            }

            val array = JSONArray(body)
            var clientNames: Map<String, String>? = null
            val projects = ArrayList<Project>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val id = item.optLong("idproyecto", -1L)
                val name = item.optString("nombre", "").ifEmpty { item.optString("titulo", "") }
                if (id == -1L || name.isEmpty()) continue

                var client = clientKeys.map { item.optString(it, "") }.firstOrNull { it.isNotEmpty() } ?: ""
                if (client.isEmpty()) {
                    val cod = item.optString("codcliente", "")
                    if (cod.isNotEmpty()) {
                        if (clientNames == null) clientNames = fetchClientNames(context)
                        client = clientNames[cod] ?: ""
                    }
                }

                projects.add(
                    Project(
                        id, name,
                        code = item.optString("pa_codigo", ""),
                        client = client,
                        address = item.optString("pa_direccion", ""),
                        municipality = item.optString("pa_municipio", "")
                    )
                )
            }

            projects.sortWith(compareByDescending<Project> { it.code }.thenBy { it.name.lowercase() })
            val cache = JSONArray()
            projects.forEach {
                cache.put(
                    JSONObject().put("id", it.id).put("name", it.name).put("code", it.code)
                        .put("client", it.client).put("address", it.address).put("municipality", it.municipality)
                )
            }
            context.config.fsProjectsCache = cache.toString()
            lastError = ""
            projects
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            null
        }
    }

    fun cachedProjects(context: Context): List<Project> {
        return try {
            val array = JSONArray(context.config.fsProjectsCache.ifEmpty { "[]" })
            (0 until array.length()).map {
                val item = array.getJSONObject(it)
                Project(
                    item.getLong("id"), item.getString("name"),
                    item.optString("code", ""), item.optString("client", ""),
                    item.optString("address", ""), item.optString("municipality", "")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun norm(text: String): String {
        val d = java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
        return d.replace(Regex("\\p{M}+"), "")
    }

    /**
     * Search by code (with or without hyphen or space), client, name, address and municipality.
     * Nothing is returned for an empty query. Case and accent insensitive, every word has to match.
     */
    fun search(projects: List<Project>, query: String, limit: Int = 8): List<Project> {
        val tokens = norm(query).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        return projects.filter { p ->
            val hay = norm(
                listOf(p.code, p.code.replace("-", ""), p.code.replace("-", " "), p.client, p.name, p.address, p.municipality)
                    .joinToString(" ")
            )
            tokens.all { hay.contains(it) }
        }.take(limit)
    }

    // ---- diary ----

    private fun entryToJson(entry: Entry) = JSONObject()
        .put("uid", entry.uid)
        .put("projectId", entry.projectId)
        .put("date", entry.date)
        .put("title", entry.title)
        .put("text", entry.text)
        .put("type", entry.type)
        .also { json ->
            entry.extras?.let { e ->
                json.put("x", JSONObject()
                    .put("ti", e.tipoint).put("in", e.interl).put("se", e.sentido).put("fr", e.fresp)
                    .put("dh", e.dias).put("es", e.estado).put("co", e.completar).put("ur", e.urgente)
                    .put("rg", e.freg).put("fe", e.fecha).put("tk", e.tk)
                    .put("tt", e.tareaTitulo).put("td", e.tareaDesc))
            }
        }

    private fun entryFromJson(item: JSONObject) = Entry(
        item.getString("uid"),
        item.getLong("projectId"),
        item.getString("date"),
        item.getString("title"),
        item.getString("text"),
        item.optString("type", "altres").ifEmpty { "altres" },
        item.optJSONObject("x")?.let { x ->
            Extras(
                x.optString("ti"), x.optString("in"), x.optString("se"), x.optString("fr"), x.optString("dh"),
                x.optString("es"), x.optBoolean("co"), x.optBoolean("ur"), x.optString("rg"), x.optString("fe"),
                x.optBoolean("tk"), x.optString("tt"), x.optString("td")
            )
        }
    )

    // one at a time, so saving the same event twice quickly cannot create two entries
    private fun send(context: Context, entry: Entry): Boolean = synchronized(sendLock) {
        sendNow(context, entry)
    }

    private fun sendNow(context: Context, entry: Entry): Boolean {
        val form = linkedMapOf(
            "uid" to entry.uid,
            "idproyecto" to entry.projectId.toString(),
            "fecha" to entry.date,
            "titulo" to entry.title,
            "descripcion" to entry.text,
            "tipo" to entry.type
        )
        entry.extras?.let { e ->
            fun put(key: String, value: String) { if (value.isNotBlank()) form[key] = value }
            put("fecharegistro", e.freg)
            put("tipointerlocutor", e.tipoint)
            put("interlocutor", e.interl.take(100))
            put("sentido", e.sentido)
            if (e.fresp.isNotBlank()) put("fecharespuesta", e.fresp) else put("diashabiles", e.dias)
            // the state is only sent when the person changed it here: the server value is the real one
            put("estado", e.estado)
            form["completar"] = if (e.completar) "1" else "0"
            form["pa_urgente"] = if (e.urgente) "1" else "0"
            if (!e.tk && e.tareaTitulo.isNotBlank()) {
                form["pa_tarea"] = "1"
                put("pa_tareatitulo", e.tareaTitulo.take(100))
                put("pa_tareadesc", e.tareaDesc)
            }
        }

        val lookup = request(
            context,
            "GET",
            "/api/3/diarioproyectos?filter[uid]=" + URLEncoder.encode(entry.uid, "UTF-8")
        )
        if (lookup.first !in 200..299) {
            lastError = "HTTP ${lookup.first}"
            return false
        }

        val existing = JSONArray(lookup.second)
        val result = if (existing.length() > 0) {
            val id = existing.getJSONObject(0).get("id").toString()
            request(context, "PUT", "/api/3/diarioproyectos/$id", form)
        } else {
            request(context, "POST", "/api/3/diarioproyectos", form)
        }

        if (result.first in 200..299) {
            lastError = ""
            return true
        }
        lastError = "HTTP ${result.first}"
        return false
    }

    /** Sends the entry now. If the server cannot be reached it is kept to be sent later. */
    fun push(context: Context, entry: Entry) {
        if (!isConfigured(context)) {
            return
        }

        val ok = try {
            send(context, entry)
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            false
        }

        if (!ok) {
            queue(context, entry)
        }
    }

    @Synchronized
    private fun queue(context: Context, entry: Entry) {
        val array = try {
            JSONArray(context.config.fsPendingQueue.ifEmpty { "[]" })
        } catch (e: Exception) {
            JSONArray()
        }

        // a newer version of the same event replaces the waiting one
        val kept = JSONArray()
        for (i in 0 until array.length()) {
            if (array.getJSONObject(i).optString("uid") != entry.uid) {
                kept.put(array.getJSONObject(i))
            }
        }
        kept.put(entryToJson(entry))
        context.config.fsPendingQueue = kept.toString()
    }

    /** Tries to send what is waiting. Call it when the app opens. */
    @Synchronized
    fun flush(context: Context) {
        if (!isConfigured(context)) {
            return
        }

        val array = try {
            JSONArray(context.config.fsPendingQueue.ifEmpty { "[]" })
        } catch (e: Exception) {
            return
        }

        val remaining = JSONArray()
        for (i in 0 until array.length()) {
            val entry = try {
                entryFromJson(array.getJSONObject(i))
            } catch (e: Exception) {
                continue
            }

            val ok = try {
                send(context, entry)
            } catch (e: Exception) {
                false
            }

            if (!ok) {
                remaining.put(array.getJSONObject(i))
            }
        }
        context.config.fsPendingQueue = remaining.toString()
    }
}
