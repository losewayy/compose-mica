package io.github.losewayy.mica

/** Resolved theme mode after applying the user's preference. */
enum class ThemeMode { LIGHT, DARK }

/** Theme preference: follow the OS, or force light/dark. */
enum class ThemePreference(val id: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromId(id: String?): ThemePreference = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/**
 * OS-level theme queries.
 *
 * Pure functions and side effects are deliberately separated: registry reads
 * spawn a process and can fail, while the value→mode mapping is pure and can be
 * exhaustively tested — "works on my machine right now" is not a test.
 */
object SystemTheme {

    /**
     * Maps a Windows `AppsUseLightTheme` registry value to a [ThemeMode].
     * Semantics: **0 = dark, 1 = light**.
     *
     * Returns DARK for anything that isn't explicitly 1 — an unreadable key and
     * a dark-mode setting are different situations upstream, but at this
     * boundary the safe fallback is dark (the common app default).
     */
    fun resolve(registryValue: Int?): ThemeMode = when (registryValue) {
        1 -> ThemeMode.LIGHT
        else -> ThemeMode.DARK
    }

    /**
     * Resolves a [ThemePreference] to a concrete [ThemeMode], consulting the OS
     * when the preference is [ThemePreference.SYSTEM].
     */
    fun resolve(preference: ThemePreference): ThemeMode = when (preference) {
        ThemePreference.LIGHT -> ThemeMode.LIGHT
        ThemePreference.DARK -> ThemeMode.DARK
        ThemePreference.SYSTEM -> resolve(readWindowsAppsUseLightTheme())
    }

    /** Reads Windows' app theme setting; null when unavailable. */
    fun readWindowsAppsUseLightTheme(): Int? = try {
        val process = ProcessBuilder(
            "reg", "query",
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
            "/v", "AppsUseLightTheme",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        // e.g. "    AppsUseLightTheme    REG_DWORD    0x0"
        val match = Regex("AppsUseLightTheme\\s+REG_DWORD\\s+0x([0-9a-fA-F]+)").find(output)
        match?.groupValues?.get(1)?.toIntOrNull(16)
    } catch (error: Throwable) {
        System.err.println("[compose-mica] system theme read failed: " + error.message)
        null
    }

    /**
     * OS "reduce motion" hint for a "system" reduced-motion option.
     *
     * On Windows the relevant toggle is "Show animations":
     * `HKCU\Control Panel\Accessibility\VisualFXSetting` (REG_DWORD, 2 = best
     * performance = animations off). Missing/unreadable returns null — callers
     * should fall back to *not* reducing motion; assuming reduction without
     * evidence is worse than assuming none.
     */
    fun reducedMotionHint(): Boolean? = try {
        val process = ProcessBuilder(
            "reg", "query",
            "HKCU\\Control Panel\\Accessibility",
            "/v", "VisualFXSetting",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        val match = Regex("VisualFXSetting\\s+REG_DWORD\\s+0x([0-9a-fA-F]+)").find(output)
        match?.groupValues?.get(1)?.toIntOrNull(16)?.let { it == 2 }
    } catch (_: Throwable) {
        null
    }
}
