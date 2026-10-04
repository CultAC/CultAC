plugins { java; cult.`format-conventions`; id("com.gradleup.shadow") }
repositories { mavenCentral() }
java.toolchain.languageVersion.set(JavaLanguageVersion.of(25))
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }

val codecs = layout.buildDirectory.dir("codecs")
val prepareCodecs by tasks.registering(Exec::class) {
    inputs.files("codecs-lock.json", rootProject.file("scripts/prepare-protocol-codecs.py"))
    outputs.files(codecs.map { it.file("ViaVersion.jar") }, codecs.map { it.file("ViaBackwards.jar") })
    commandLine("python3", rootProject.file("scripts/prepare-protocol-codecs.py"),
        "--lock", file("codecs-lock.json"), "--output", codecs.get().asFile)
    // Optional verified development cache; clean builds use the pinned public sources.
    val cache = rootProject.file(".ignored/compatibility-2026-10-03/translation-source-build")
    if (cache.isDirectory) args("--artifact-cache", cache)
}
dependencies {
    compileOnly(project(":protocol"))
    implementation(files(codecs.map { it.file("ViaVersion.jar") }, codecs.map { it.file("ViaBackwards.jar") }))
    compileOnly("io.netty:netty-transport:4.2.16.Final")
    compileOnly("io.netty:netty-codec-base:4.2.16.Final")
    compileOnly("com.google.guava:guava:33.5.0-jre")
    testImplementation(project(":protocol"))
    testImplementation("io.netty:netty-transport:4.2.16.Final")
    testImplementation("io.netty:netty-codec-base:4.2.16.Final")
    testImplementation("com.google.guava:guava:33.5.0-jre")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.compileJava { dependsOn(prepareCodecs) }
tasks.test {
    dependsOn(prepareCodecs)
    useJUnitPlatform()
    // Via intentionally permits one manager per classloader; each integration test owns one.
    forkEvery = 1
    val fixtures = layout.buildDirectory.dir("test-fixtures")
    outputs.dir(fixtures)
    systemProperty("wireValueFixtures", fixtures.get().asFile.absolutePath)
}
tasks.processTestResources {
    dependsOn(tasks.shadowJar)
    from(tasks.shadowJar.flatMap { it.archiveFile }) { into("runtime") }
}
val modelJava = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
for (verify in listOf(false, true)) {
    tasks.register<Exec>(if (verify) "verifyModelRegistries" else "exportModelRegistries") {
        group = if (verify) "verification" else "protocol"
        commandLine("python3", rootProject.file("scripts/generate-model-registries.py"),
            "--lock", rootProject.file("protocol/model-registries-lock.json"),
            "--java", modelJava.get().executablePath.asFile,
            "--work", layout.buildDirectory.dir("model-reports").get().asFile,
            "--cache", layout.buildDirectory.dir("model-downloads").get().asFile,
            "--output", rootProject.file("protocol/src/main/resources/ac/cult/cultac/protocol/model"))
        if (verify) args("--verify")
    }
}
tasks.shadowJar {
    archiveFileName.set("protocol-codecs.jar")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    doLast {
        check(ProcessBuilder("python3", rootProject.file("scripts/verify-no-bundled-minecraft.py").path,
            archiveFile.get().asFile.path).inheritIO().start().waitFor() == 0)
    }
}
