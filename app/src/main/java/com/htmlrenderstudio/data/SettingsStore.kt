package com.htmlrenderstudio.data

import android.content.Context

class SettingsStore(context: Context) {
    private val sp = context.getSharedPreferences("render_settings", Context.MODE_PRIVATE)

    fun load(): RenderSettings {
        val d = RenderSettings()
        return RenderSettings(
            resolution = runCatching { Resolution.valueOf(sp.getString("res", null) ?: "") }.getOrDefault(d.resolution),
            fps = sp.getInt("fps", d.fps).takeIf { it in listOf(24, 30, 60) } ?: d.fps,
            ratio = runCatching { AspectRatio.valueOf(sp.getString("ratio", null) ?: "") }.getOrDefault(d.ratio),
            autoDuration = sp.getBoolean("auto", d.autoDuration),
            customSeconds = sp.getFloat("custom", d.customSeconds),
        )
    }

    fun save(s: RenderSettings) {
        sp.edit()
            .putString("res", s.resolution.name)
            .putInt("fps", s.fps)
            .putString("ratio", s.ratio.name)
            .putBoolean("auto", s.autoDuration)
            .putFloat("custom", s.customSeconds)
            .apply()
    }
}
