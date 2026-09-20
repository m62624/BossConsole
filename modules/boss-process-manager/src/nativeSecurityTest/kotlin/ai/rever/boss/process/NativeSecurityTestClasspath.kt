package ai.rever.boss.process

internal fun nativeSecurityClasspath(): String =
    (System.getProperty("boss.test.classpath") ?: System.getProperty("java.class.path"))
        ?.takeIf { it.isNotBlank() }
        ?: error("Native security test requires a JVM classpath")
