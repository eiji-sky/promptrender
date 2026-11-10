package com.htmlrenderstudio.ui

import android.app.Application
import android.net.Uri
import android.widget.FrameLayout
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.htmlrenderstudio.data.AppStorage
import com.htmlrenderstudio.data.HistoryEntry
import com.htmlrenderstudio.data.Project
import com.htmlrenderstudio.data.RenderSettings
import com.htmlrenderstudio.data.SettingsStore
import com.htmlrenderstudio.importer.ProjectImporter
import com.htmlrenderstudio.importer.ZipImportException
import com.htmlrenderstudio.preview.PageAnalysis
import com.htmlrenderstudio.render.EncoderException
import com.htmlrenderstudio.render.Phase
import com.htmlrenderstudio.render.RenderEngine
import com.htmlrenderstudio.render.RenderException
import com.htmlrenderstudio.storage.StorageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class MainTab(val label: String) { HOME("Home"), PROJECTS("Projects"), HISTORY("History"), SETTINGS("Settings") }

sealed interface Screen {
    data class Tab(val tab: MainTab) : Screen
    data class ProjectDetail(val id: String) : Screen
    data object Rendering : Screen
    data class Result(val historyId: String) : Screen
}

data class ImportUi(val busy: Boolean = false, val message: String = "", val error: String? = null)

data class RenderUi(
    val active: Boolean = false,
    val phase: Phase = Phase.LOADING,
    val frame: Int = 0,
    val total: Int = 0,
    val elapsedMs: Long = 0,
    val message: String = "Menyiapkan...",
    val error: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val storage = AppStorage(app)
    private val settingsStore = SettingsStore(app)
    private val importer = ProjectImporter(app, storage)
    private val files = StorageManager(app, storage)
    private val engine = RenderEngine(app)

    private val _screen = MutableStateFlow<Screen>(Screen.Tab(MainTab.HOME))
    val screen: StateFlow<Screen> = _screen.asStateFlow()
    private var lastTab = MainTab.HOME

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<RenderSettings> = _settings.asStateFlow()

    private val _import = MutableStateFlow(ImportUi())
    val importState: StateFlow<ImportUi> = _import.asStateFlow()

    private val _pending = MutableStateFlow<ProjectImporter.Draft?>(null)
    val pendingImport: StateFlow<ProjectImporter.Draft?> = _pending.asStateFlow()

    private val _analysis = MutableStateFlow<PageAnalysis?>(null)
    val analysis: StateFlow<PageAnalysis?> = _analysis.asStateFlow()

    private val _render = MutableStateFlow(RenderUi())
    val render: StateFlow<RenderUi> = _render.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var renderJob: Job? = null
    private var currentProjectId: String? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            storage.clearTemp()
            refresh()
        }
    }

    private fun refresh() {
        _projects.value = storage.loadProjects()
        _history.value = storage.loadHistory()
    }

    private fun say(msg: String) {
        _messages.tryEmit(msg)
    }

    // ---------- Navigasi ----------

    fun selectTab(tab: MainTab) {
        lastTab = tab
        _screen.value = Screen.Tab(tab)
    }

    fun openProject(id: String) {
        currentProjectId = id
        _analysis.value = null
        _screen.value = Screen.ProjectDetail(id)
    }

    fun openResult(id: String) {
        _screen.value = Screen.Result(id)
    }

    fun back() {
        when (_screen.value) {
            is Screen.Tab -> if (lastTab != MainTab.HOME) selectTab(MainTab.HOME)
            is Screen.ProjectDetail -> selectTab(if (lastTab == MainTab.HOME) MainTab.PROJECTS else lastTab)
            is Screen.Result -> selectTab(MainTab.HISTORY)
            is Screen.Rendering -> cancelRender()
        }
    }

    // ---------- Import ----------

    fun importZip(uri: Uri) = runImport { importer.importFromUri(uri) }

    fun importSample() = runImport { importer.importSample() }

    private fun runImport(block: () -> ProjectImporter.Draft) {
        if (_import.value.busy) return
        _import.value = ImportUi(busy = true, message = "Mengekstrak dan memvalidasi project...")
        viewModelScope.launch {
            try {
                val draft = withContext(Dispatchers.IO) { block() }
                _import.value = ImportUi()
                if (draft.htmlFiles.size > 1) _pending.value = draft else finishImport(draft, draft.htmlFiles.first())
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _import.value = ImportUi(
                    error = when (t) {
                        is ZipImportException -> t.message
                        is OutOfMemoryError -> "Memori tidak cukup untuk mengimpor file ini."
                        else -> "Gagal mengimpor: ${t.message ?: t.javaClass.simpleName}"
                    }
                )
            }
        }
    }

    fun confirmPending(entry: String) {
        val draft = _pending.value ?: return
        _pending.value = null
        viewModelScope.launch { finishImport(draft, entry) }
    }

    fun cancelPending() {
        val draft = _pending.value ?: return
        _pending.value = null
        viewModelScope.launch(Dispatchers.IO) { File(storage.projectsRoot, draft.project.id).deleteRecursively() }
    }

    private suspend fun finishImport(draft: ProjectImporter.Draft, entry: String) {
        val p = draft.project.copy(entry = entry)
        withContext(Dispatchers.IO) {
            storage.saveProject(p)
            refresh()
        }
        openProject(p.id)
    }

    fun dismissImportError() {
        _import.value = ImportUi()
    }

    fun deleteProject(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            storage.deleteProject(id)
            refresh()
        }
    }

    fun contentDir(p: Project): File = storage.contentDir(p)

    // ---------- Settings & analisis ----------

    fun updateSettings(s: RenderSettings) {
        _settings.value = s
        settingsStore.save(s)
    }

    fun setAnalysis(a: PageAnalysis?) {
        _analysis.value = a
    }

    fun tempBytes(): Long = storage.tempBytes()

    fun clearTemp() {
        viewModelScope.launch(Dispatchers.IO) {
            storage.clearTemp()
            say("File sementara dihapus.")
        }
    }

    // ---------- Render ----------

    fun beginRender() {
        val s = _settings.value
        if (!s.autoDuration && s.customSeconds !in 0.5f..600f) {
            say("Durasi custom harus antara 0,5 dan 600 detik.")
            return
        }
        val a = _analysis.value
        if (s.autoDuration && a != null && a.durationMs == null) {
            say("Durasi otomatis tidak terdeteksi. Pakai Custom duration.")
            return
        }
        _render.value = RenderUi()
        _screen.value = Screen.Rendering
    }

    fun startRender(host: FrameLayout) {
        if (renderJob?.isActive == true) return
        val project = _projects.value.firstOrNull { it.id == currentProjectId } ?: return
        val s = _settings.value
        val out = storage.newTempFile()
        _render.value = RenderUi(active = true)

        renderJob = viewModelScope.launch {
            try {
                val res = engine.render(host, storage.contentDir(project), project.entry, s, out) { p ->
                    _render.value = RenderUi(
                        active = true,
                        phase = p.phase,
                        frame = p.frame,
                        total = p.totalFrames,
                        elapsedMs = p.elapsedMs,
                        message = p.message,
                    )
                }
                val entry = withContext(Dispatchers.IO) {
                    files.commitRender(out, project.id, project.name, res).also { refresh() }
                }
                _render.value = RenderUi()
                if (res.warnings.isNotEmpty()) say("Selesai dengan ${res.warnings.size} peringatan kompatibilitas.")
                _screen.value = Screen.Result(entry.id)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { out.delete() }
                throw e
            } catch (t: Throwable) {
                out.delete()
                _render.value = RenderUi(error = friendly(t))
            }
        }
    }

    fun cancelRender() {
        renderJob?.cancel()
        renderJob = null
        _render.value = RenderUi()
        val id = currentProjectId
        _screen.value = if (id != null) Screen.ProjectDetail(id) else Screen.Tab(MainTab.HOME)
        say("Render dibatalkan. File sementara dibersihkan.")
    }

    fun leaveRenderError() {
        _render.value = RenderUi()
        val id = currentProjectId
        _screen.value = if (id != null) Screen.ProjectDetail(id) else Screen.Tab(MainTab.HOME)
    }

    private fun friendly(t: Throwable): String = when (t) {
        is OutOfMemoryError -> "Memori perangkat tidak cukup. Coba resolusi atau FPS yang lebih rendah."
        is RenderException -> t.message ?: "Render gagal."
        is EncoderException -> t.message ?: "Encoder gagal."
        else -> "Render gagal: ${t.message ?: t.javaClass.simpleName}"
    }

    // ---------- Hasil & riwayat ----------

    fun renderFile(e: HistoryEntry): File = storage.renderFile(e)

    fun deleteHistory(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            storage.deleteHistory(id)
            refresh()
        }
    }

    fun saveToGallery(e: HistoryEntry) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { files.saveToGallery(e) }
                say("Tersimpan di galeri (Movies/HTMLRenderStudio).")
            } catch (t: Throwable) {
                say("Gagal menyimpan ke galeri: ${t.message}")
            }
        }
    }

    fun copyToUri(e: HistoryEntry, uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { files.copyToUri(e, uri) }
                say("Video disimpan.")
            } catch (t: Throwable) {
                say("Gagal menyimpan: ${t.message}")
            }
        }
    }

    fun suggestedName(e: HistoryEntry) = files.suggestedName(e)

    fun shareIntent(e: HistoryEntry) = files.shareIntent(e)

    fun renderAgain(e: HistoryEntry) {
        if (_projects.value.any { it.id == e.projectId }) openProject(e.projectId)
        else say("Project asalnya sudah dihapus.")
    }

    override fun onCleared() {
        renderJob?.cancel()
        super.onCleared()
    }
}
