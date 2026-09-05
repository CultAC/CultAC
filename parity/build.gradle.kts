plugins {
    id("java")
}

group = "ac.cult.cultac"
description = "Differential conformance harness for the pinned Cult runtime checks"

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

val cultParityAgentJar = tasks.register<Jar>("cultParityAgentJar") {
    group = "verification"
    description = "Packages the ASM agent used by the live Cult parity harness."
    archiveFileName.set("cult-parity-agent.jar")
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.isFile }
            .map(::zipTree)
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest.attributes(
        "Premain-Class" to "ac.cult.cultac.parity.agent.CultParityAgent",
        "Can-Redefine-Classes" to "true",
        "Can-Retransform-Classes" to "true"
    )
}

tasks.register<JavaExec>("cultParity") {
    group = "verification"
    description = "Runs inventory, static parity, scenario validation, and live trace comparison."
    dependsOn(tasks.named("classes"), cultParityAgentJar)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("ac.cult.cultac.parity.CultParityMain")
    maxHeapSize = "2g"

    fun property(name: String, default: String): Provider<String> =
        providers.gradleProperty(name).orElse(default)

    fun value(name: String, default: String): String = property(name, default).get()

    args(
        "--checks", value("cultParity.checks", "all"),
        "--artifact-root", value("cultParity.artifactRoot", layout.buildDirectory.dir("cult-parity").get().asFile.absolutePath),
        "--baseline-ref", value("cultParity.baselineRef", "73a835d04f9bfbc76dc1d41c24ea73f3aec2280c"),
        "--baseline-commit", value("cultParity.baselineCommit", "73a835d04f9bfbc76dc1d41c24ea73f3aec2280c"),
        "--current-commit", value("cultParity.currentCommit", ""),
        "--baseline-jar", value("cultParity.baselineJar", ""),
        "--current-jar", value("cultParity.currentJar", ""),
        "--baseline-source", value("cultParity.baselineSource", ""),
        "--current-source", value("cultParity.currentSource", rootProject.projectDir.absolutePath),
        "--baseline-inventory", value("cultParity.baselineInventory", ""),
        "--current-inventory", value("cultParity.currentInventory", ""),
        "--scenario-catalog", value("cultParity.scenarioCatalog", rootProject.file("parity/scenarios/shared-check-scenarios.json").absolutePath),
        "--mapping-file", value("cultParity.mappingFile", rootProject.file("parity/config/check-equivalence-mappings.tsv").absolutePath),
        "--classification-file", value("cultParity.classificationFile", rootProject.file("parity/config/check-classifications.tsv").absolutePath),
        "--repository-root", value("cultParity.repositoryRoot", rootProject.projectDir.absolutePath),
        "--live-command", value("cultParity.liveCommand", "")
    )
}
