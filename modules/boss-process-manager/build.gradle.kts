import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import java.nio.file.Files
import java.nio.file.Path

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
// only by an explicit native-security workflow and must fail when native
// capability prerequisites are unavailable.
val nativeSecurityTestSourceSet = sourceSets.create("nativeSecurityTest")
nativeSecurityTestSourceSet.kotlin.srcDir("src/nativeSecurityTest/kotlin")
nativeSecurityTestSourceSet.compileClasspath += sourceSets.main.get().output
nativeSecurityTestSourceSet.runtimeClasspath += sourceSets.main.get().runtimeClasspath

configurations[nativeSecurityTestSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[nativeSecurityTestSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

val nativeSecurityTest =
    tasks.register<Test>("nativeSecurityTest") {
        group = "verification"
        description =
            "Runs Cageforge native enforcement smoke tests in a prepared native environment"
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
        val qemuLauncher = rootProject.file("ci/cageforge-qemu-suite/run.sh")
        val bossIpcJar = project(":boss-ipc").tasks.named<Jar>("jar")
        val bossNativeFilesJar = project(":boss-native-files").tasks.named<Jar>("jar")
        val mainRuntimeClasspath = sourceSets.main.get().runtimeClasspath
        val nativeRuntimeClasspath = nativeSecurityTestSourceSet.runtimeClasspath
        inputs.files(nativeSecurityTestSourceSet.runtimeClasspath)
        inputs.file(qemuLauncher)
        dependsOn(nativeSecurityTestSourceSet.classesTaskName, bossIpcJar, bossNativeFilesJar)
        from(sourceSets.main.get().output) {
            into("classes")
        }
        from(nativeSecurityTestSourceSet.output) {
            into("classes")
        }
        from({
            val runtimeClasspathFiles = linkedSetOf<File>()
            mainRuntimeClasspath.files.filterTo(runtimeClasspathFiles, File::isFile)
            nativeRuntimeClasspath.files.filterTo(runtimeClasspathFiles, File::isFile)
            runtimeClasspathFiles.removeIf {
                it.name.startsWith("boss-ipc-") || it.name.startsWith("boss-native-files-")
            }
            runtimeClasspathFiles
        }) {
            into("lib")
        }
        from(bossIpcJar.flatMap { it.archiveFile }) {
            into("lib")
        }
        from(bossNativeFilesJar.flatMap { it.archiveFile }) {
            into("lib")
        }
        from(qemuLauncher) {
            into("ci/cageforge-qemu-suite")
        }
    }

val windowsNativeSecuritySetup =
    tasks.register<JavaExec>("windowsNativeSecuritySetup") {
        group = "verification"
        description = "Explicitly provisions Cageforge prerequisites for the Windows security lane"
        dependsOn(nativeSecurityTestSourceSet.classesTaskName)
        mainClass.set("ai.rever.boss.process.CageforgeWindowsSetupMainKt")
        classpath = nativeSecurityTestSourceSet.runtimeClasspath
    }

val windowsNativeSecurityTeardown =
    tasks.register<JavaExec>("windowsNativeSecurityTeardown") {
        group = "verification"
        description = "Removes Cageforge prerequisites provisioned by the Windows security lane"
        dependsOn(nativeSecurityTestSourceSet.classesTaskName)
        mainClass.set("ai.rever.boss.process.CageforgeWindowsSetupMainKt")
        classpath = nativeSecurityTestSourceSet.runtimeClasspath
        args("teardown")
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
        val temporaryRoot =
            if (
                !System.getProperty("os.name").startsWith("Windows", ignoreCase = true) &&
                Files.isDirectory(Path.of(File.separator, "tmp"))
            ) {
                Path.of(File.separator, "tmp")
            } else {
                Path.of(System.getProperty("java.io.tmpdir"))
            }
        systemProperty("boss.data.dir", Files.createTempDirectory(temporaryRoot, "bs").toString())
        val stagedClasspath =
            System.getenv("CAGEFORGE_NATIVE_SECURITY_CLASSPATH").takeIf { !it.isNullOrBlank() }
        val existingClasspath =
            classpath.asPath
                .split(File.pathSeparator)
                .filter { File(it).exists() }
                .joinToString(File.pathSeparator)
        systemProperty(
            "boss.test.classpath",
            if (name == "nativeSecurityTest") {
                stagedClasspath ?: existingClasspath
            } else {
                classpath.asPath
            },
        )
    }
}
