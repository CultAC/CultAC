plugins {
    id("java")
}

group = "ac.grim.grimac"
description = "Differential conformance harness for the pinned Grim runtime checks"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.ow2.asm:asm:9.8")
    implementation("org.ow2.asm:asm-commons:9.8")
    implementation("org.ow2.asm:asm-tree:9.8")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}

val grimParityAgentJar = tasks.register<Jar>("grimParityAgentJar") {
    group = "verification"
    description = "Packages the ASM agent used by the live Grim parity harness."
    archiveFileName.set("grim-parity-agent.jar")
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.isFile }
            .map(::zipTree)
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest.attributes(
        "Premain-Class" to "ac.grim.grimac.parity.agent.GrimParityAgent",
        "Can-Redefine-Classes" to "true",
        "Can-Retransform-Classes" to "true"
    )
}

tasks.register<JavaExec>("grimParity") {
    group = "verification"
    description = "Runs inventory, static parity, scenario validation, and live trace comparison."
    dependsOn(tasks.named("classes"), grimParityAgentJar)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("ac.grim.grimac.parity.GrimParityMain")
    maxHeapSize = "2g"

    fun property(name: String, default: String): Provider<String> =
        providers.gradleProperty(name).orElse(default)

    fun value(name: String, default: String): String = property(name, default).get()

    args(
        "--checks", value("grimParity.checks", "all"),
        "--artifact-root", value("grimParity.artifactRoot", layout.buildDirectory.dir("grim-parity").get().asFile.absolutePath),
        "--baseline-ref", value("grimParity.baselineRef", "73a835d04f9bfbc76dc1d41c24ea73f3aec2280c"),
        "--baseline-commit", value("grimParity.baselineCommit", "73a835d04f9bfbc76dc1d41c24ea73f3aec2280c"),
        "--current-commit", value("grimParity.currentCommit", ""),
        "--baseline-jar", value("grimParity.baselineJar", ""),
        "--current-jar", value("grimParity.currentJar", ""),
        "--baseline-source", value("grimParity.baselineSource", ""),
        "--current-source", value("grimParity.currentSource", rootProject.projectDir.absolutePath),
        "--baseline-inventory", value("grimParity.baselineInventory", ""),
        "--current-inventory", value("grimParity.currentInventory", ""),
        "--scenario-catalog", value("grimParity.scenarioCatalog", rootProject.file("parity/scenarios/shared-check-scenarios.json").absolutePath),
        "--mapping-file", value("grimParity.mappingFile", rootProject.file("parity/config/check-equivalence-mappings.tsv").absolutePath),
        "--classification-file", value("grimParity.classificationFile", rootProject.file("parity/config/check-classifications.tsv").absolutePath),
        "--repository-root", value("grimParity.repositoryRoot", rootProject.projectDir.absolutePath),
        "--live-command", value("grimParity.liveCommand", "")
    )
}
