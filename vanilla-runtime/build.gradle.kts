plugins { `java-library`; cult.`format-conventions` }

repositories {
    if (providers.gradleProperty("mavenLocalOverride").getOrElse("false").toBoolean()) mavenLocal()
    maven("https://maven.grim.ac/public/releases")
    maven("https://maven.grim.ac/public/snapshots")
    maven("https://repo.grim.ac/snapshots")
    maven("https://nexus.scarsz.me/content/repositories/releases")
    mavenCentral()
}
java.toolchain.languageVersion.set(JavaLanguageVersion.of(25))
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }
configurations.configureEach {
    attributes.attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
}

val runtimeFiles = layout.buildDirectory.dir("runtime")
val prepareVanilla = tasks.register<Exec>("prepareVanilla") {
    group = "build setup"
    description = "Downloads and verifies the pinned vanilla 26.3 model and libraries."
    val lock = layout.projectDirectory.file("26.3-lock.json")
    val script = rootProject.layout.projectDirectory.file("scripts/prepare-vanilla-runtime.py")
    inputs.files(lock, script)
    outputs.dir(runtimeFiles)
    commandLine("python3", script.asFile, "--lock", lock.asFile, "--output", runtimeFiles.get().asFile)
    // Reuse independently verified smoketest downloads when present; these are optional caches.
    val clientCache = rootProject.file(".ignored/vanilla-26.3/projects/mcp/build/mcp/downloadClient/client.jar")
    val libraryCache = rootProject.file(".ignored/vanilla-26.3/libraries")
    if (clientCache.isFile) args("--client-cache", clientCache)
    if (libraryCache.isDirectory) args("--library-cache", libraryCache)
}

val vanilla = files(runtimeFiles.map { it.file("client.jar") }) + fileTree(runtimeFiles.map { it.dir("lib") }) {
    include("**/*.jar")
}
dependencies {
    compileOnly(project(":common")) { isTransitive = false }
    compileOnly(project(":protocol"))
    compileOnly(project(":placement-api"))
    compileOnly(vanilla)
    compileOnly("org.jetbrains:annotations:24.1.0")
    testImplementation(project(":common"))
    testImplementation(project(":placement-runtime"))
    testImplementation(project(":protocol-codec"))
    testImplementation(files(rootProject.project(":protocol-codec").layout.buildDirectory.file("codecs/ViaVersion.jar")))
    testImplementation(vanilla)
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(libs.cult.internal.shims)
}
tasks.compileJava { dependsOn(prepareVanilla) }

tasks.test {
    dependsOn(prepareVanilla, ":placement-runtime:jar", ":protocol-codec:test")
    systemProperty("wireValueFixtures", project(":protocol-codec").layout.buildDirectory.dir("test-fixtures").get().asFile.absolutePath)
    systemProperty("placementRuntimeJar", project(":placement-runtime").layout.buildDirectory.file("libs/placement-runtime.jar").get().asFile.absolutePath)
    useJUnitPlatform()
    maxHeapSize = "1g"
}

val contextAgent = tasks.register<Jar>("contextTestAgent") {
    from(sourceSets.test.get().output)
    archiveFileName.set("context-test-agent.jar")
    manifest.attributes["Premain-Class"] = "ac.cult.cultac.vanilla.ContextTestAgent"
}
tasks.test {
    dependsOn(contextAgent)
    jvmArgs("-javaagent:" + contextAgent.get().archiveFile.get().asFile.absolutePath)
}
