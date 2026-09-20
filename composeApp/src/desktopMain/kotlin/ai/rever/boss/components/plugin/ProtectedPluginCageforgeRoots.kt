package ai.rever.boss.components.plugin

import java.io.File

internal fun protectedExecutableRoots(nativeImage: String?): List<File> =
    buildList {
        add(
            File(System.getProperty("java.home")).canonicalFile.also {
                require(it.isDirectory) {
                    "Protected Java runtime root must be an existing directory: ${it.path}"
                }
            },
        )
        nativeImage?.let { image ->
            add(
                File(image).canonicalFile.parentFile?.also {
                    require(it.isDirectory) {
                        "Protected native image runtime root must be an existing directory: ${it.path}"
                    }
                } ?: error("Protected native image must have an executable parent directory"),
            )
        }
    }.distinctBy { it.path }
