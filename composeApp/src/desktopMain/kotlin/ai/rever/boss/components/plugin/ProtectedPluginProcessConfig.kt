package ai.rever.boss.components.plugin

import ai.rever.boss.ipc.IpcAddressResolver
import ai.rever.boss.performance.PerformanceSettingsManager
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.process.CageforgePolicyCeiling
import ai.rever.boss.process.ProcessConfig
import ai.rever.boss.process.ProcessSpawner
import ai.rever.boss.process.ProcessType
import ai.rever.boss.process.RestartPolicy
import java.io.File

internal data class ProtectedPluginProcessConfig(
    val manifest: PluginManifest,
    val jarPath: String,
    val securityRequired: Boolean,
    val runtimeClasspath: String,
    val windowId: String,
    val projectPath: String,
)

internal fun buildProcessConfig(input: ProtectedPluginProcessConfig): ProcessConfig {
    val manifest = input.manifest
    val workDir = validateWorkDir(input.projectPath)
    val pluginJar = validatePluginJar(input.jarPath)
    val runtimeJar = validateRuntimeJar(input.runtimeClasspath)
    val apiJar = validateApiJar()
    val nativeImage = validateNativeImage(manifest.nativeImagePath, input.securityRequired)
    val classpath = listOfNotNull(runtimeJar.path, pluginJar.path, apiJar).joinToString(File.pathSeparator)
    val protectedRoots =
        if (input.securityRequired) {
            protectedClasspathRoots(classpath, nativeImage)
        } else {
            emptyList()
        }
    val processId = pluginProcessId(input.windowId, manifest.pluginId)
    val localIpcPaths =
        if (input.securityRequired) {
            listOf(
                IpcAddressResolver.kernelAddress(),
                IpcAddressResolver.resolveAddress("plugin", processId),
            ).map { address ->
                require(address.startsWith("unix://")) {
                    "Protected Cageforge IPC requires a Unix-domain transport on this platform: $address"
                }
                address.removePrefix("unix://")
            }
        } else {
            emptyList()
        }

    return ProcessConfig(
        processId = processId,
        processType = ProcessType.PLUGIN,
        displayName = manifest.displayName,
        mainClass = "ai.rever.boss.plugin.runtime.PluginProcessMainKt",
        classpath = classpath,
        nativeImagePath = nativeImage,
        jvmArgs = buildJvmArgs(workDir, input.securityRequired),
        workDir = workDir,
        restartPolicy = RestartPolicy.ON_FAILURE,
        maxRestarts = manifest.sandbox.maxRestartAttempts,
        environment = buildEnvironment(pluginJar.path, workDir.path, input.windowId, manifest.pluginId),
        startupTimeoutMs = manifest.healthContract?.startupTimeoutMs ?: 30_000,
        heartbeatIntervalMs = manifest.healthContract?.heartbeatIntervalMs ?: 5_000,
        cageforge =
            if (input.securityRequired) {
                CageforgePolicyCeiling
                    .forWorkspace(workDir, protectedRoots)
                    .policyFor(workDir, localIpcPaths)
            } else {
                null
            },
    )
}

private fun validateWorkDir(projectPath: String): File {
    val requested = File(projectPath.ifEmpty { System.getProperty("user.dir") })
    require(requested.isAbsolute) {
        "Protected plugin working directory must be absolute: ${requested.path}"
    }
    val workDir = requested.canonicalFile
    require(workDir.isDirectory) {
        "Protected plugin working directory must exist: ${workDir.path}"
    }
    return workDir
}

private fun validatePluginJar(jarPath: String): File {
    val input = File(jarPath)
    require(input.isAbsolute) { "Protected plugin JAR path must be absolute: ${input.path}" }
    return input.canonicalFile.also {
        require(it.isFile) { "Plugin JAR does not exist: ${it.path}" }
    }
}

private fun validateRuntimeJar(runtimeClasspath: String): File {
    val runtimeJar = File(runtimeClasspath)
    require(runtimeJar.isAbsolute) {
        "Protected plugin runtime JAR path must be absolute: ${runtimeJar.path}"
    }
    return runtimeJar.canonicalFile.also {
        require(it.isFile) { "Plugin runtime JAR does not exist: ${it.path}" }
    }
}

private fun validateApiJar(): String? {
    val apiJar = System.getProperty("boss.api.jar")?.takeIf { it.isNotBlank() } ?: return null
    val input = File(apiJar)
    require(input.isAbsolute) { "Protected API JAR path must be absolute: ${input.path}" }
    return input.canonicalFile
        .also {
            require(it.isFile) { "Protected API JAR does not exist: ${it.path}" }
        }.path
}

private fun validateNativeImage(
    nativeImagePath: String?,
    securityRequired: Boolean,
): String? {
    val path = nativeImagePath?.takeIf { it.isNotEmpty() }
    return when {
        path == null -> {
            null
        }

        !securityRequired -> {
            path
        }

        else -> {
            val image = File(path)
            require(image.isAbsolute) {
                "Protected native image path must be absolute: ${image.path}"
            }
            image.canonicalFile
                .also {
                    require(it.isFile && it.canExecute()) {
                        "Protected native image must be an executable file: ${it.path}"
                    }
                }.path
        }
    }
}

private fun protectedClasspathRoots(
    classpath: String,
    nativeImage: String?,
): List<File> =
    buildList {
        addAll(classpathRoots(System.getProperty("java.class.path")))
        addAll(classpathRoots(classpath))
        val javaHome =
            File(System.getProperty("java.home"))
                .also {
                    require(it.isAbsolute && it.isDirectory) {
                        "java.home must be an absolute directory: ${it.path}"
                    }
                }.normalize()
        add(javaHome)
        File(javaHome, "conf/security").canonicalFile.takeIf { it.isDirectory }?.let(::add)
        add(
            File(ProcessSpawner.findJavaExecutable())
                .also {
                    require(it.isAbsolute && it.isFile && it.canExecute()) {
                        "Protected Java executable must be absolute and executable: ${it.path}"
                    }
                }.normalize(),
        )
        nativeImage?.let(::File)?.let(::add)
    }.distinctBy { it.path }

private fun buildJvmArgs(
    workDir: File,
    securityRequired: Boolean,
): List<String> =
    buildList {
        val settings = runCatching { PerformanceSettingsManager.currentSettings.value }.getOrNull()
        add("-Xmx${settings?.pluginJvmHeapMb ?: 512}m")
        add("-Xms${settings?.pluginJvmInitialHeapMb ?: 64}m")
        if (securityRequired) {
            // Cageforge keeps the host temp directory private. Netty's Unix transport extracts
            // its native library at startup, so give the protected child an explicit writable
            // location inside its already-authorized workspace instead of reopening /tmp.
            add("-Dio.netty.native.workdir=${workDir.path}")
        }
        System.getProperty("boss.api.version")?.takeIf { it.isNotBlank() }?.let {
            add("-Dboss.api.version=$it")
        }
    }

private fun buildEnvironment(
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
