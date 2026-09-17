package ai.rever.boss.process

import ai.cageforge.Cageforge
import ai.cageforge.RuntimeContext
import ai.rever.boss.ipc.IpcAddressResolver
import ai.rever.boss.ipc.auth.IpcEnvironment
import ai.rever.boss.ipc.auth.IpcTlsIdentity
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Spawns child processes (either GraalVM native images or JVM subprocesses).
 *
 * Each child process receives:
 * - BOSS_KERNEL_IPC_ADDR: Address to connect back to the kernel
 * - BOSS_PROCESS_ID: Assigned process ID
 * - BOSS_PROCESS_TYPE: Process type (SERVICE, APP, PLUGIN)
 *
 * Process stdout/stderr are drained into bounded logs under $BOSS_DATA_DIR/logs/{processId}/.
 * Each stream retains at most five 10 MiB files, including the current file.
 *
 * Everything spawned here is entered into [registry], because the registry is what the kernel's
 * shutdown hook reaps on exit. Registration used to be each caller's job, and the caller that
 * forgot - the out-of-process plugin spawner - leaked a full cohort of child JVMs on every host
 * exit for months. Owning it here makes that class of bug impossible for every call site.
 */
class ProcessSpawner
    @JvmOverloads
    constructor(
        private val kernelIpcAddress: String,
        private val logDir: File =
            File(
                System.getenv("BOSS_DATA_DIR")
                    ?: "${System.getProperty("user.home")}/.boss",
                "logs",
            ),
        private val registry: ProcessRegistry? = null,
        /** The host registry is required for a managed IPC child. Plain subprocesses may omit it. */
        private val tokenRegistry: ProcessTokenRegistry? = null,
        private val kernelIdentity: IpcTlsIdentity? = null,
    ) {
        private val logger = LoggerFactory.getLogger(ProcessSpawner::class.java)

        /**
         * Spawn a new child process from the given configuration and register it.
         *
         * If a native image path is specified and the binary exists, it runs natively.
         * Otherwise falls back to JVM mode.
         *
         * The returned process is already in [registry], so callers must not register it again.
         * Removing it is still the caller's job: only the caller knows the difference between a
         * deliberate termination and a crash.
         */
        fun spawn(config: ProcessConfig): ManagedProcess {
            // Validate before socket or log creation, not after a caller-selected directory is made.
            IpcAddressResolver.validateProcessIdentifier(config.processId)
            val ipcAddress =
                IpcAddressResolver.resolveAddress(
                    config.processType.name.lowercase(),
                    config.processId,
                )

            val command = buildCommand(config)

            logger.info(
                "Spawning process: id={}, type={}",
                config.processId,
                config.processType,
            )

            val childEnvironment = buildChildEnvironment(config, ipcAddress)

            val logs = ProcessLogStreams.acquire(logDir.toPath(), config.processId)
            var security: SpawnIpcSecurity? = null
            val process =
                runCatching {
                    security = SpawnIpcSecurity.create(tokenRegistry, kernelIdentity, config, ipcAddress)
                    security?.install(childEnvironment)
                    startProcess(command, config, childEnvironment, logs)
                }.onFailure {
                    try {
                        logs.close()
                    } finally {
                        security?.revoke()
                    }
                }.getOrThrow()
            process.onExit().thenRun { security?.revoke() }

            logger.info(
                "Process started: id={}, pid={}, ipc={}",
                config.processId,
                process.pid(),
                ipcAddress,
            )

            return ManagedProcess(
                config = config,
                process = process,
                ipcAddress = ipcAddress,
            ).also {
                it.ipcClient = security?.client
                registry?.register(config.processId, it)
            }
        }

        private fun buildChildEnvironment(
            config: ProcessConfig,
            ipcAddress: String,
        ): MutableMap<String, String> =
            linkedMapOf<String, String>().apply {
                putAll(config.environment)
                put("BOSS_KERNEL_IPC_ADDR", kernelIpcAddress)
                put("BOSS_PROCESS_ID", config.processId)
                put("BOSS_PROCESS_TYPE", config.processType.name)
                put("BOSS_IPC_ADDR", ipcAddress)
                IpcEnvironment.removeCredentials(this)
                // The kernel owns the credential values and they are never logged.
            }

        private fun startProcess(
            command: List<String>,
            config: ProcessConfig,
            environment: Map<String, String>,
            logs: ProcessLogStreams,
        ): Process {
            if (config.cageforge != null) {
                return startWithCageforge(command, config, environment).also { attachLogs(it, logs) }
            }

            val processBuilder = ProcessBuilder(command).directory(config.workDir)
            processBuilder.environment().putAll(environment)
            return startWithLogs(processBuilder, logs)
        }

        private fun startWithCageforge(
            command: List<String>,
            config: ProcessConfig,
            environment: Map<String, String>,
        ): Process {
            val policy = checkNotNull(config.cageforge)
            val workDir = validateProtectedWorkDir(config)
            validateProtectedExecutable(command)
            val runtimeContext = RuntimeContext(currentDirectory = workDir.toPath())
            Cageforge.checkToml(policy.toml, policy.profileName, runtimeContext)
            val runtime =
                Cageforge.fromToml(
                    policy.toml,
                    policy.profileName,
                    runtimeContext,
                )
            var managed: CageforgeManagedProcess? = null
            return runCatching {
                val child = CageforgeManagedProcess(runtime.launch(buildBootstrapArgv(command, workDir)), runtime)
                managed = child
                ProtectedEnvironmentChannel.send(
                    child.inputStream,
                    child.outputStream,
                    environment,
                    config.startupTimeoutMs,
                )
                child
            }.onFailure {
                managed?.destroyForcibly()
                managed?.onExit()?.join()
                runtime.close()
            }.getOrThrow()
        }

        private fun validateProtectedWorkDir(config: ProcessConfig): File {
            require(config.workDir.isAbsolute) {
                "Protected process working directory must be absolute"
            }
            val workDir = config.workDir.canonicalFile
            require(workDir.isDirectory) {
                "Protected process working directory must be an existing absolute directory"
            }
            return workDir
        }

        private fun validateProtectedExecutable(command: List<String>) {
            require(command.isNotEmpty()) { "Protected process argv must not be empty" }
            command.forEachIndexed { index, argument ->
                require('\u0000' !in argument) {
                    "Protected process argv[$index] must not contain NUL"
                }
            }
            val executable = File(command.first())
            require(executable.isAbsolute) { "Protected process executable must be absolute" }
            val canonicalExecutable = executable.canonicalFile
            require(canonicalExecutable.isFile && canonicalExecutable.canExecute()) {
                "Protected process executable is not executable: ${canonicalExecutable.path}"
            }
        }

        private fun buildBootstrapArgv(
            command: List<String>,
            workDir: File,
        ): List<String> {
            val java = File(findJavaExecutable()).canonicalFile
            require(java.isAbsolute && java.isFile && java.canExecute()) {
                "Protected bootstrap requires an absolute executable Java runtime: ${java.path}"
            }
            val classpath =
                System.getProperty("java.class.path")?.takeIf { it.isNotBlank() }
                    ?: error("Protected launch requires the current JVM classpath")
            return buildList {
                add(java.path)
                add("-cp")
                add(classpath)
                add(ProtectedChildBootstrap::class.java.name)
                add("--cwd")
                add(workDir.path)
                add("--")
                addAll(command)
            }
        }

        private fun attachLogs(
            process: Process,
            logs: ProcessLogStreams,
        ) {
            var attached = false
            try {
                logs.attach(process)
                attached = true
            } finally {
                if (!attached) {
                    process.destroyForcibly()
                    process.onExit().join()
                }
            }
        }

        private fun startWithLogs(
            builder: ProcessBuilder,
            logs: ProcessLogStreams,
        ): Process {
            val child = builder.start()
            var attached = false
            try {
                logs.attach(child)
                attached = true
                return child
            } finally {
                if (!attached) {
                    child.destroyForcibly()
                    child.onExit().join()
                }
            }
        }

        private fun buildCommand(config: ProcessConfig): List<String> {
            val nativeBinary = config.nativeImagePath

            // Prefer native image if available
            if (nativeBinary != null && File(nativeBinary).let { it.exists() && it.canExecute() }) {
                logger.info("Using GraalVM native image for {}: {}", config.processId, nativeBinary)
                return listOf(nativeBinary)
            }

            // Fall back to JVM mode
            val javaExecutable = findJavaExecutable()
            logger.info("Using JVM mode for {}: {}", config.processId, javaExecutable)

            return buildList {
                add(javaExecutable)
                addAll(config.jvmArgs)
                if (config.classpath.isNotBlank()) {
                    add("-cp")
                    add(config.classpath)
                }
                add(config.mainClass)
            }
        }

        companion object {
            fun findJavaExecutable(): String {
                // Use the same Java that's running the kernel — but only if it IS java.
                // In packaged app bundles, the current command is the app launcher (e.g., "BOSS"),
                // not the java binary. In that case fall back to JAVA_HOME.
                val currentCommand =
                    ProcessHandle
                        .current()
                        .info()
                        .command()
                        .orElse(null)
                if (currentCommand != null &&
                    (
                        currentCommand.endsWith("/java") || currentCommand.endsWith("\\java.exe") ||
                            currentCommand.endsWith("/java.exe")
                    )
                ) {
                    return currentCommand
                }
                // Not a JVM launcher — fall back to JAVA_HOME or java.home system property
                val executableName =
                    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                        "java.exe"
                    } else {
                        "java"
                    }
                System.getenv("JAVA_HOME")?.let { return File(File(it, "bin"), executableName).path }
                return System.getProperty("java.home")?.let { File(File(it, "bin"), executableName).path }
                    ?: executableName
            }
        }
    }
