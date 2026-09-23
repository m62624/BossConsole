plugins {
    alias(libs.plugins.kotlinJvm)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

dependencies {
    implementation(libs.cageforge.java)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test.junit)
}

// Never attached to check/build: native enforcement requires a prepared OS.
val nativeSecurity = sourceSets.create("nativeSecurityTest")
nativeSecurity.compileClasspath += sourceSets.main.get().output
nativeSecurity.runtimeClasspath += sourceSets.main.get().output
configurations[nativeSecurity.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[nativeSecurity.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

tasks.register<Test>("nativeSecurityTest") {
    group = "verification"
    description = "Tests root and descendant isolation on a prepared native backend"
    testClassesDirs = nativeSecurity.output.classesDirs
    classpath = nativeSecurity.runtimeClasspath
}

tasks.register<Tar>("nativeSecurityTestBundle") {
    group = "verification"
    description = "Compiles and packages the native consumer before QEMU starts"
    archiveFileName.set("boss-command-sandbox-tests.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("nativeSecurityTest"))
    compression = Compression.GZIP
    dependsOn(nativeSecurity.classesTaskName)
    from(sourceSets.main.get().output) { into("classes") }
    from(nativeSecurity.output) { into("classes") }
    from(configurations[nativeSecurity.runtimeClasspathConfigurationName]) { into("lib") }
    from(rootProject.file("ci/cageforge-command-suite/run.sh")) { into("ci/cageforge-command-suite") }
}

tasks.withType<Test>().configureEach {
    val testHome = layout.buildDirectory.dir("test-home/$name")
    systemProperty("user.home", testHome.get().asFile.absolutePath)
    doFirst {
        systemProperty("boss.sandbox.test.classpath", classpath.asPath)
        testHome.get().asFile.deleteRecursively()
        testHome.get().asFile.mkdirs()
    }
}
