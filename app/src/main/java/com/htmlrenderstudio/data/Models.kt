package com.htmlrenderstudio.data

enum class Resolution(val label: String, val shortSide: Int) {
    P720("720p", 720),
    P1080("1080p", 1080),
}

enum class AspectRatio(val label: String, val w: Int, val h: Int) {
    LANDSCAPE("16:9", 16, 9),
    PORTRAIT("9:16", 9, 16),
    SQUARE("1:1", 1, 1),
}

data class RenderSettings(
    val resolution: Resolution = Resolution.P720,
    val fps: Int = 30,
    val ratio: AspectRatio = AspectRatio.LANDSCAPE,
    val autoDuration: Boolean = false,
    val customSeconds: Float = 10f,
) {
    /** Ukuran video (lebar, tinggi). 720p = sisi pendek 720, 1080p = sisi pendek 1080. */
    fun dimensions(): Pair<Int, Int> {
        val s = resolution.shortSide
        val long = s * 16 / 9
        return when (ratio) {
            AspectRatio.LANDSCAPE -> long to s
            AspectRatio.PORTRAIT -> s to long
            AspectRatio.SQUARE -> s to s
        }
    }
}

data class Project(
    val id: String,
    val name: String,
    /** Folder dasar project, relatif terhadap folder project (mis. "content" atau "content/MyAnim"). */
    val contentRoot: String,
    /** File HTML utama, relatif terhadap contentRoot, pakai '/'. */
    val entry: String,
    val importedAt: Long,
    val zipBytes: Long,
    val extractedBytes: Long,
    val fileCount: Int,
)

data class HistoryEntry(
    val id: String,
    val projectId: String,
    val projectName: String,
    /** Nama file MP4 di folder renders/. */
    val fileName: String,
    val createdAt: Long,
    val width: Int,
    val height: Int,
    val fps: Int,
    val frames: Int,
    val durationMs: Long,
    val renderMs: Long,
    val sizeBytes: Long,
)
