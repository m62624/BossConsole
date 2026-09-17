import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import java.nio.file.Files

plugins {
    alias(libs.plugins.kotlinJvm)
}

group = "ai.rever.boss.process"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    // IPC protocol definitions and connection management
    api(project(":boss-ipc"))
    implementation(project(":boss-native-files"))

    // Native OS-level boundary for protected child processes. The binding ships
    // the reviewed platform resources; the host kernel still has to support the
    // selected backend capabilities.
    implementation(libs.cageforge.java)

    // Kotlin coroutines
    api(libs.kotlinx.coroutines.core)

    // Logging
    implementation(libs.slf4j.api)

    // Testing
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Native enforcement tests are intentionally a separate source set and task.
// Ordinary `test` remains portable JVM/process/IPC coverage; this task is run
// only by the prepared Linux QEMU/KVM job and must fail when native capability
// prerequisites are unavailable.
val nativeSecurityTestSourceSet = sourceSets.create("nativeSecurityTest")
nativeSecurityTestSourceSet.kotlin.srcDir("src/nativeSecurityTest/kotlin")
nativeSecurityTestSourceSet.compileClasspath += sourceSets.main.get().output
nativeSecurityTestSourceSet.runtimeClasspath += sourceSets.main.get().output

configurations[nativeSecurityTestSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[nativeSecurityTestSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

val nativeSecurityTest =
    tasks.register<Test>("nativeSecurityTest") {
        group = "verification"
        description = "Runs Cageforge native enforcement smoke tests in a prepared Linux guest"
        dependsOn(nativeSecurityTestSourceSet.classesTaskName)
        testClassesDirs = nativeSecurityTestSourceSet.output.classesDirs
        classpath = nativeSecurityTestSourceSet.runtimeClasspath
        useJUnitPlatform()
        failOnNoDiscoveredTests = true
        systemProperty(
            "user.home",
            layout.buildDirectory
                .dir("test-home/$name")
                .get()
                .asFile.absolutePath,
        )
        doFirst {
            val osName = System.getProperty("os.name").orEmpty()
            if (!osName.startsWith("Linux", ignoreCase = true)) {
                throw GradleException(
                    "nativeSecurityTest requires a Linux guest with Cageforge native capabilities; " +
                        "run ordinary process/IPC tests on this host and run this task in the QEMU job",
                )
            }
        }
    }

// The QEMU lane executes this prebuilt consumer inside the restricted guest.
// Compilation and dependency resolution stay in the ordinary Linux job so the
// guest proves native enforcement without repeating the full Gradle build.
val nativeSecurityTestBundle =
    tasks.register<Tar>("nativeSecurityTestBundle") {
        group = "verification"
        description = "Bundles the native security smoke consumer for the QEMU guest"
        archiveFileName.set("boss-native-security-test-bundle.tar.gz")
        destinationDirectory.set(layout.buildDirectory.dir("nativeSecurityTest"))
        compression = Compression.GZIP
        dependsOn(nativeSecurityTestSourceSet.classesTaskName)
        from(sourceSets.main.get().output) {
            into("classes")
        }
        from(nativeSecurityTestSourceSet.output) {
            into("classes")
        }
        from({
            nativeSecurityTestSourceSet.runtimeClasspath.files.filter(File::isFile)
        }) {
            into("lib")
        }
    }

tasks.withType<Test>().configureEach {
    // IPC address resolution caches its data directory; isolate it before the test JVM starts.
    val testHome =
        layout.buildDirectory
            .dir("test-home/$name")
            .get()
            .asFile
    systemProperty("user.home", testHome.absolutePath)
    doFirst {
        testHome.deleteRecursively()
        testHome.mkdirs()
        // A short isolated directory keeps actual Unix socket names below the platform limit.
        systemProperty("boss.data.dir", Files.createTempDirectory("bs").toString())
        systemProperty("boss.test.classpath", classpath.asPath)
    }
}
