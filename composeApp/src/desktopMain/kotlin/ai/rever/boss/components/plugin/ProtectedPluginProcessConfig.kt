package ai.rever.boss.components.plugin

import ai.rever.boss.ipc.IpcAddressResolver
import ai.rever.boss.performance.PerformanceSettingsManager
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.process.CageforgeLocalIpcEndpoint
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
    val sandboxRequest: PluginSandboxRequest = PluginSandboxRequest.EMPTY,
)

internal data class PreparedProtectedPluginLaunch(
    val processConfig: ProcessConfig,
    val sandboxRequest: PluginSandboxRequest,
)

private data class ProtectedLaunchContext(
    val manifest: PluginManifest,
    val pluginJar: File,
    val classpath: String,
    val nativeImage: String?,
    val workDir: File,
    val processId: String,
    val protectedRoots: ProtectedRoots,
    val localIpcEndpoints: List<CageforgeLocalIpcEndpoint>,
    val validatedSandboxRequest: ValidatedPluginSandboxRequest,
)

internal fun buildProcessConfig(input: ProtectedPluginProcessConfig): ProcessConfig = input.prepare().processConfig

internal fun ProtectedPluginProcessConfig.prepare(): PreparedProtectedPluginLaunch {
    val input = this
    val manifest = input.manifest
    val workDir = validateWorkDir(input.projectPath)
    val pluginJar = validatePluginJar(input.jarPath)
    val runtimeJar = validateRuntimeJar(input.runtimeClasspath)
    val apiJar = validateApiJar()
    val nativeImage = validateNativeImage(manifest.nativeImagePath, input.securityRequired)
    val classpath = listOfNotNull(runtimeJar.path, pluginJar.path, apiJar).joinToString(File.pathSeparator)
    val protectedRoots =
        if (input.securityRequired) {
            protectedClasspathRoots(classpath, nativeImage, workDir)
        } else {
            ProtectedRoots(emptyList(), emptyList())
        }
    val processId = pluginProcessId(input.windowId, manifest.pluginId)
    val localIpcEndpoints =
        if (input.securityRequired) {
            // Both directions are host-owned launch capabilities: the child connects to the
            // kernel, and the host connects back to the child after it registers. Windows
            // materializes both named-pipe instances before Cageforge preflight.
            listOf(
                IpcAddressResolver.kernelAddress(),
                IpcAddressResolver.resolveAddress("plugin", pluginProcessId(input.windowId, manifest.pluginId)),
            ).map(::parseProtectedLocalIpcEndpoint)
        } else {
            emptyList()
        }
    val validatedSandboxRequest =
        validatePluginSandboxRequest(
            request = input.sandboxRequest,
            securityRequired = input.securityRequired,
            workspace = workDir,
            protectedRoots = protectedRoots,
            hostEndpoints = localIpcEndpoints,
        )

    val context =
        ProtectedLaunchContext(
            manifest = manifest,
            pluginJar = pluginJar,
            classpath = classpath,
            nativeImage = nativeImage,
            workDir = workDir,
            processId = processId,
            protectedRoots = protectedRoots,
            localIpcEndpoints = localIpcEndpoints,
            validatedSandboxRequest = validatedSandboxRequest,
        )
    return PreparedProtectedPluginLaunch(
        processConfig = buildProtectedProcessConfig(input, context),
        sandboxRequest = context.validatedSandboxRequest.request,
    )
}

private fun buildProtectedProcessConfig(
    input: ProtectedPluginProcessConfig,
    context: ProtectedLaunchContext,
): ProcessConfig =
    ProcessConfig(
        processId = context.processId,
        processType = ProcessType.PLUGIN,
        displayName = context.manifest.displayName,
        mainClass = "ai.rever.boss.plugin.runtime.PluginProcessMainKt",
        classpath = context.classpath,
        nativeImagePath = context.nativeImage,
        jvmArgs = buildJvmArgs(context.workDir, input.securityRequired),
        workDir = context.workDir,
        restartPolicy = RestartPolicy.ON_FAILURE,
        maxRestarts = context.manifest.sandbox.maxRestartAttempts,
        environment =
            buildEnvironment(
                context.pluginJar.path,
                context.workDir.path,
                input.windowId,
                context.manifest.pluginId,
            ),
        startupTimeoutMs = context.manifest.healthContract?.startupTimeoutMs ?: 30_000,
        heartbeatIntervalMs = context.manifest.healthContract?.heartbeatIntervalMs ?: 5_000,
        cageforge =
            buildCageforgePolicy(
                input.securityRequired,
                context.workDir,
                context.protectedRoots,
                context.localIpcEndpoints,
                context.validatedSandboxRequest.requestedReadOnlyRoots,
            ),
    )

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

internal data class ProtectedRoots(
    val readOnlyRoots: List<File>,
    val executableRoots: List<File>,
)

private fun protectedClasspathRoots(
    classpath: String,
    nativeImage: String?,
    workDir: File,
): ProtectedRoots {
    val javaHome =
        File(System.getProperty("java.home"))
            .also {
                require(it.isAbsolute && it.isDirectory) {
                    "java.home must be an absolute directory: ${it.path}"
                }
            }.canonicalFile
    val javaExecutable =
        File(ProcessSpawner.findJavaExecutable())
            .also {
                require(it.isAbsolute && it.isFile && it.canExecute()) {
                    "Protected Java executable must be absolute and executable: ${it.path}"
                }
            }.canonicalFile
    val readOnlyRoots =
        buildList {
            addAll(classpathRoots(System.getProperty("java.class.path")))
            addAll(classpathRoots(classpath))
            add(javaHome)
            File(javaHome, "conf").canonicalFile.takeIf { it.isDirectory }?.let(::add)
            File(javaHome, "conf/security").canonicalFile.takeIf { it.isDirectory }?.let(::add)
            File(javaHome, "conf/security/policy").canonicalFile.takeIf { it.isDirectory }?.let(::add)
            File(javaHome, "conf/security/policy/unlimited").canonicalFile.takeIf { it.isDirectory }?.let(::add)
            File(javaHome, "conf/security/policy/unlimited/default_US_export.policy")
                .canonicalFile
                .takeIf { it.isFile }
                ?.let(::add)
            File(javaHome, "conf/security/policy/unlimited/default_local.policy")
                .canonicalFile
                .takeIf { it.isFile }
                ?.let(::add)
            File(javaHome, "conf/security/java.security.d").canonicalFile.takeIf { it.isDirectory }?.let(::add)
            File(javaHome, "conf/security/java.security").canonicalFile.takeIf { it.isFile }?.let(::add)
            add(javaExecutable)
            nativeImage?.let(::File)?.let(::add)
        }.distinctBy { it.path }
    val executableRoots =
        buildList {
            add(javaHome)
            javaExecutable.parentFile?.takeIf { it.isDirectory }?.let(::add)
            nativeImage
                ?.let(::File)
                ?.canonicalFile
                ?.parentFile
                ?.takeIf { it.isDirectory }
                ?.let(::add)
            if (System.getProperty("os.name").contains("mac", ignoreCase = true)) {
                // Netty's Unix transport extracts its Mach-O library into the explicit
                // io.netty.native.workdir used by protected launches. Seatbelt keeps
                // file reads separate from executable mapping, so this writable BOSS
                // workspace must be declared as a runtime root as well.
                add(workDir)
            }
        }.distinctBy { it.path }
    return ProtectedRoots(readOnlyRoots, executableRoots)
}

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
