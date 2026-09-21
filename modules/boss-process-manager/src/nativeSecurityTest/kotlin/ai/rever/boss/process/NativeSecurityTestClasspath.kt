package ai.rever.boss.process

import java.io.File

internal fun nativeSecurityClasspath(): String =
    (System.getProperty("boss.test.classpath") ?: System.getProperty("java.class.path"))
        ?.takeIf { it.isNotBlank() }
        ?: error("Native security test requires a JVM classpath")

/**
 * Returns the filesystem roots needed by the child classpath. Windows Cageforge ACLs are
 * directory-scoped, so a JAR contributes its containing directory rather than a separate file
 * root; other platforms retain the exact classpath entries.
 */
internal fun nativeSecurityReadRoots(classpath: String): List<File> =
    buildList {
        for (entry in classpath.split(File.pathSeparator).filter(String::isNotBlank).map(::File)) {
            val canonical = entry.canonicalFile
            add(
                if (isWindowsHost() && canonical.isFile) {
                    requireNotNull(canonical.parentFile) {
                        "Native security classpath entry has no parent: ${canonical.path}"
                    }
                } else {
                    canonical
                },
            )
        }
    }.distinctBy(File::getPath)

internal fun isWindowsHost(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
