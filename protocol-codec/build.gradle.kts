import java.util.zip.ZipFile

plugins { java; cult.`format-conventions`; id("com.gradleup.shadow") }
repositories {
    maven("https://repo.viaversion.com") { content { includeGroup("com.viaversion") } }
    mavenCentral()
}
java.toolchain.languageVersion.set(JavaLanguageVersion.of(25))
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }

// Shade published Maven artifacts. The adapter compiles in :protocol,
// which consumes this relocated artifact without a provider/API dependency cycle.
dependencies {
    implementation(libs.via.codecs.core) { isTransitive = false }
    implementation(libs.via.codecs.backwards) { isTransitive = false }
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
val supportedDataVersions = setOf(
    "1.21", "1.21.2", "1.21.4", "1.21.5", "1.21.6", "1.21.7",
    "1.21.9", "1.21.11", "26.1", "26.2", "26.3"
)

tasks.shadowJar {
    archiveFileName.set("vialib.jar")
    relocate("com.viaversion.viaversion", "ac.cult.shaded.vialib")
    relocate("com.viaversion.viabackwards", "ac.cult.shaded.vialib.backwards")
    relocate("com.viaversion.nbt", "ac.cult.shaded.vialib.nbt")
    relocate("assets.viaversion", "assets.ac.cult.shaded.vialib.core")
    relocate("assets.viabackwards", "assets.ac.cult.shaded.vialib.backwards")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("plugin.yml", "paper-plugin.yml", "velocity-plugin.json", "fabric.mod.json")
    exclude("com/viaversion/viaversion/bukkit/**", "com/viaversion/viaversion/velocity/**")
    exclude("com/viaversion/viabackwards/BukkitPlugin*", "com/viaversion/viabackwards/VelocityPlugin*",
        "com/viaversion/viabackwards/ViaFabricAddon*", "com/viaversion/viabackwards/listener/**",
        "com/viaversion/viabackwards/provider/**")
    exclude("assets/*/textures/**")
    // Shared identifiers and auxiliary registries remain complete. Only unsupported
    // version-specific mapping/identifier files are removed; global indexes stay intact.
    exclude {
        val name = it.file.name
        when {
            name.startsWith("mappings-") && name.endsWith(".nbt") ->
                name.removePrefix("mappings-").removeSuffix(".nbt").split("to").any { version -> version !in supportedDataVersions }
            name.startsWith("identifiers-") && name.endsWith(".nbt") ->
                name.removePrefix("identifiers-").removeSuffix(".nbt") !in supportedDataVersions
            else -> false
        }
    }
    doLast {
        check(ProcessBuilder("python3", rootProject.file("scripts/verify-no-bundled-minecraft.py").path,
            archiveFile.get().asFile.path).inheritIO().start().waitFor() == 0)
        ZipFile(archiveFile.get().asFile).use { archive ->
            check(archive.getEntry("ac/cult/shaded/vialib/api/Via.class") != null)
            check(archive.getEntry("assets/ac/cult/shaded/vialib/core/data/identifier-table.nbt") != null)
            check(archive.entries().asSequence().none { it.name.startsWith("com/viaversion/") || it.name.startsWith("assets/viaversion/") || it.name.startsWith("assets/viabackwards/") })
        }
    }
}
