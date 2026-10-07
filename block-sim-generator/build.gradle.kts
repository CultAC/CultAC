plugins { `java-library` }

group = rootProject.group
version = rootProject.version

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
repositories { mavenCentral() }

dependencies {
    implementation("com.github.javaparser:javaparser-core:3.27.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Only explicit regeneration resolves or compiles the pinned Minecraft worker.
val vanilla by sourceSets.creating
val vanillaJar = providers.gradleProperty("blockSimVanillaJar")
val vanillaLibraries = providers.gradleProperty("blockSimVanillaLibraries")
val vanillaClasspath = files(provider {
    check(vanillaJar.isPresent && vanillaLibraries.isPresent) {
        "Regeneration requires -PblockSimVanillaJar and -PblockSimVanillaLibraries."
    }
    listOf(file(vanillaJar.get())) + file(vanillaLibraries.get()).readLines()
        .filter { it.startsWith("-e=") }.map { file(it.removePrefix("-e=")) }
})
vanilla.compileClasspath += vanillaClasspath
vanilla.runtimeClasspath += vanillaClasspath

tasks.register<JavaExec>("regenerateBlockSimData") {
    group = "generation"
    description = "Regenerates bundled 26.3 tables from the SHA-256 pinned client jar."
    dependsOn(vanilla.classesTaskName, tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("ac.cult.blocksim.generator.GenerationLauncher")
    maxHeapSize = "2g"
    args(providers.gradleProperty("blockSimOutput").getOrElse(rootProject.file("block-sim/src/main/resources/block-sim/26.3").absolutePath))
    args(vanillaJar.getOrElse(""), vanillaLibraries.getOrElse(""))
    args(vanilla.output.classesDirs.asPath)
    args(layout.buildDirectory.dir("vanilla-report").get().asFile.absolutePath)
    args(providers.gradleProperty("blockSimHandlesOutput").getOrElse(rootProject.file("block-sim/src/main/java/ac/cult/blocksim/data/BlockIds.java").absolutePath))
    args(providers.gradleProperty("blockSimPropertiesOutput").getOrElse(rootProject.file("block-sim/src/main/java/ac/cult/blocksim/data/BlockProps.java").absolutePath))
}

// The world registry facts can be regenerated without rerunning block/shape probes.
tasks.register<JavaExec>("regenerateClientWorldDefaults") {
    group = "generation"
    description = "Regenerates bundled world registry defaults from the pinned 26.3 client."
    dependsOn(vanilla.classesTaskName, tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("ac.cult.blocksim.generator.GenerationLauncher")
    maxHeapSize = "1g"
    args(providers.gradleProperty("clientWorldDefaultsOutput").getOrElse(rootProject.file("block-sim/src/main/resources/block-sim/26.3/client-world-defaults.bin.gz").absolutePath))
    args(vanillaJar.getOrElse(""), vanillaLibraries.getOrElse(""))
    args(vanilla.output.classesDirs.asPath)
    args(layout.buildDirectory.dir("vanilla-report").get().asFile.absolutePath)
    args("--world-registries-only")
}

tasks.register<JavaExec>("scanVanillaSources") {
    group = "generation"
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("ac.cult.blocksim.generator.SourceScanner")
    args(providers.gradleProperty("blockSimSourceRoot").getOrElse(""))
    args(rootProject.file("block-sim/manifest/26.3-surface.tsv").absolutePath)
}

tasks.register<JavaExec>("verifySourceManifest") {
    group = "verification"
    description = "Explicitly checks the audited method hashes against a supplied pinned source checkout."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("ac.cult.blocksim.generator.SourceScanner")
    args(providers.gradleProperty("blockSimSourceRoot").getOrElse(""))
    args(rootProject.file("block-sim/manifest/26.3-surface.tsv").absolutePath, "--verify")
}

tasks.test { useJUnitPlatform() }
