package com.htmlrenderstudio.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Persistensi sederhana (JSON) untuk daftar project dan riwayat render, plus lokasi folder kerja. */
class AppStorage(context: Context) {
    val projectsRoot = File(context.filesDir, "projects").apply { mkdirs() }
    val rendersRoot = File(context.filesDir, "renders").apply { mkdirs() }
    val tempRoot = File(context.filesDir, "render_tmp").apply { mkdirs() }

    private val projectsFile = File(context.filesDir, "projects.json")
    private val historyFile = File(context.filesDir, "history.json")
    private val lock = Any()

    fun newTempFile(): File = File(tempRoot, "render_${UUID.randomUUID()}.mp4")

    fun clearTemp() {
        tempRoot.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun tempBytes(): Long = tempRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun projectDir(p: Project) = File(projectsRoot, p.id)
    fun contentDir(p: Project) = File(projectDir(p), p.contentRoot)
    fun renderFile(e: HistoryEntry) = File(rendersRoot, e.fileName)

    // ---- Projects ----

    fun loadProjects(): List<Project> = synchronized(lock) {
        val arr = readArray(projectsFile)
        (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                Project(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    contentRoot = o.getString("contentRoot"),
                    entry = o.getString("entry"),
                    importedAt = o.getLong("importedAt"),
                    zipBytes = o.optLong("zipBytes"),
                    extractedBytes = o.optLong("extractedBytes"),
                    fileCount = o.optInt("fileCount"),
                )
            }.getOrNull()
        }.filter { File(projectsRoot, it.id).isDirectory }
    }

    fun saveProject(p: Project) = synchronized(lock) {
        val list = loadProjects().filter { it.id != p.id } + p
        writeAtomic(projectsFile, JSONArray().also { arr ->
            list.sortedByDescending { it.importedAt }.forEach { arr.put(projectToJson(it)) }
        }.toString())
    }

    fun deleteProject(id: String) = synchronized(lock) {
        File(projectsRoot, id).deleteRecursively()
        val list = loadProjects().filter { it.id != id }
        writeAtomic(projectsFile, JSONArray().also { arr -> list.forEach { arr.put(projectToJson(it)) } }.toString())
    }

    // ---- History ----

    fun loadHistory(): List<HistoryEntry> = synchronized(lock) {
        val arr = readArray(historyFile)
        (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                HistoryEntry(
                    id = o.getString("id"),
                    projectId = o.getString("projectId"),
                    projectName = o.getString("projectName"),
                    fileName = o.getString("fileName"),
                    createdAt = o.getLong("createdAt"),
                    width = o.getInt("width"),
                    height = o.getInt("height"),
                    fps = o.getInt("fps"),
                    frames = o.getInt("frames"),
                    durationMs = o.getLong("durationMs"),
                    renderMs = o.getLong("renderMs"),
                    sizeBytes = o.getLong("sizeBytes"),
                )
            }.getOrNull()
        }.filter { File(rendersRoot, it.fileName).isFile }
    }

    fun addHistory(e: HistoryEntry) = synchronized(lock) {
        val list = listOf(e) + loadHistory().filter { it.id != e.id }
        writeHistory(list)
    }

    fun deleteHistory(id: String) = synchronized(lock) {
        val all = loadHistory()
        all.firstOrNull { it.id == id }?.let { File(rendersRoot, it.fileName).delete() }
        writeHistory(all.filter { it.id != id })
    }

    private fun writeHistory(list: List<HistoryEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id); put("projectId", e.projectId); put("projectName", e.projectName)
                put("fileName", e.fileName); put("createdAt", e.createdAt)
                put("width", e.width); put("height", e.height); put("fps", e.fps)
                put("frames", e.frames); put("durationMs", e.durationMs)
                put("renderMs", e.renderMs); put("sizeBytes", e.sizeBytes)
            })
        }
        writeAtomic(historyFile, arr.toString())
    }

    private fun projectToJson(p: Project) = JSONObject().apply {
        put("id", p.id); put("name", p.name); put("contentRoot", p.contentRoot); put("entry", p.entry)
        put("importedAt", p.importedAt); put("zipBytes", p.zipBytes)
        put("extractedBytes", p.extractedBytes); put("fileCount", p.fileCount)
    }

    private fun readArray(f: File): JSONArray = try {
        if (f.exists()) JSONArray(f.readText()) else JSONArray()
    } catch (e: Exception) {
        JSONArray()
    }

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }
}
