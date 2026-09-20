package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.pathutils.BossDirectories
import java.io.File

internal fun buildEnvironment(
    jarPath: String,
    workingDirectory: String,
    windowId: String,
    pluginId: String,
): Map<String, String> =
    buildMap {
        put("BOSS_PLUGIN_CLASSPATH", jarPath)
        put("BOSS_PLUGIN_ID", pluginId)
        if (windowId.isNotBlank()) put("BOSS_WINDOW_ID", windowId)
        put("BOSS_PROJECT_PATH", workingDirectory)
    }

internal fun resolveRuntimeClasspath(): String {
    val configured = System.getenv("BOSS_PLUGIN_RUNTIME_JAR")
    val discovered =
        runCatching { BossDirectories.rootDir }
            .getOrElse {
                File(
                    System.getenv("BOSS_DATA_DIR")
                        ?: File(System.getProperty("user.home"), ".boss").path,
                )
            }.let { File(it, "plugins") }
            .listFiles()
            ?.filter {
                it.name.startsWith(MicrokernelRuntime.ARTIFACT_PREFIX) && it.name.endsWith(".jar")
            }?.maxByOrNull { it.lastModified() }
            ?.canonicalPath
    return configured
        ?: discovered
        ?: error("Cannot find ${MicrokernelRuntime.ARTIFACT_PREFIX} JAR. Set BOSS_PLUGIN_RUNTIME_JAR env var.")
}
