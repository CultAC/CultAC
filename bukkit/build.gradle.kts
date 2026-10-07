import net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import versioning.BuildConfig
import java.util.zip.ZipFile

plugins {
    `maven-publish`
    cult.`base-conventions`
    cult.`shadow-conventions`
    id("de.eldoria.plugin-yml.bukkit") version "0.8.0"
    id("xyz.jpenilla.run-paper") version "3.0.0-beta.1"
    id("io.papermc.paperweight.userdev")
}

repositories {
    val localOverride = if (BuildConfig.mavenLocalOverride) mavenLocal() else null

    // Exclusive Repositories (One HTTP request per dep)
    exclusive("https://repo.papermc.io/repository/maven-public/", { name = "papermc" }) {
        includeGroup("net.md-5")
    }

    exclusive("https://libraries.minecraft.net", { mavenContent { releasesOnly() } }) {
        includeModule("com.mojang", "brigadier")
    }

    exclusive("https://repo.extendedclip.com/content/repositories/placeholderapi/") {
        includeGroup("me.clip")
    }

    val cultPublicReleases = maven("https://maven.grim.ac/public/releases") {
        mavenContent { releasesOnly() }
    }
    val cultPublicSnapshots = maven("https://maven.grim.ac/public/snapshots") {
        mavenContent { snapshotsOnly() }
    }
    val cultLegacySnapshots = maven("https://repo.grim.ac/snapshots")
    exclusiveContent {
        forRepositories(*listOfNotNull(localOverride, cultPublicReleases, cultPublicSnapshots, cultLegacySnapshots).toTypedArray())
        filter {
            includeGroup("ac.grim.grimac")
        }
    }

    exclusive("https://nexus.scarsz.me/content/repositories/releases", { mavenContent { releasesOnly() } }) {
        includeGroup("github.scarsz")
    }

    mavenCentral()
}

// Compile the host boundary against Paper's Java 25 API while emitting Java 21 bytecode.
configurations.configureEach {
    attributes.attribute(
        org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE,
        25
    )
}

// Resolve the shared engine's standard runtime jar for the shaded distributable.
configurations.named("shadow") {
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

dependencies {
    paperweight.paperDevBundle(providers.gradleProperty("paperDevBundleVersion").get())

    compileOnly(libs.placeholderapi)
    compileOnly(libs.luckperms)

    implementation(libs.cloud.paper)
    implementation(libs.adventure.platform.bukkit)
    implementation(libs.cult.bukkit.internal)

    implementation(project(":common"))
    implementation(project(":protocol-netty"))
    shadow(project(":common"))
}

bukkit {
    name = "CultAC"
    provides = listOf("GrimAC")
    author = "CultAC"
    main = "ac.cult.cultac.platform.bukkit.CultACBukkitLoaderPlugin"
    website = "https://github.com/CultAC/CultAC"
    apiVersion = "1.13"
    foliaSupported = true

    softDepend = listOf(
        "ProtocolLib",
        "ProtocolSupport",
        "Essentials",
        "ViaVersion",
        "ViaBackwards",
        "ViaRewind",
        "Geyser-Spigot",
        "floodgate",
        "FastLogin",
        "PlaceholderAPI",
        "LuckPerms",
        // Driver holder mods — softdepend so each backend's driver class
        // resolves through the linked classloader.
        "sqlite-jdbc",
        "mysql-jdbc",
        "postgresql-jdbc",
        "mongodb-driver",
        "jedis",
    )

    permissions {
        register("cult.alerts") {
            description = "Receive alerts for violations"
            default = Permission.Default.OP
        }

        register("cult.alerts.enable-on-join") {
            description = "Enable alerts on join"
            default = Permission.Default.OP
        }

        register("cult.performance") {
            description = "Check performance metrics"
            default = Permission.Default.OP
        }

        register("cult.profile") {
            description = "Check user profile"
            default = Permission.Default.OP
        }

        register("cult.brand") {
            description = "Show client brands on join"
            default = Permission.Default.OP
        }

        register("cult.brand.enable-on-join") {
            description = "Enable showing client brands on join"
            default = Permission.Default.OP
        }

        register("cult.sendalert") {
            description = "Send cheater alert"
            default = Permission.Default.OP
        }

        register("cult.nosetback") {
            description = "Disable setback"
            default = Permission.Default.FALSE
        }

        register("cult.nomodifypacket") {
            description = "Disable modifying packets"
            default = Permission.Default.FALSE
        }

        register("cult.disabled") {
            description = "Disable Cult checks while keeping player state tracked"
            default = Permission.Default.FALSE
        }

        register("cult.exempt") {
            description = "Exempt from all checks"
            default = Permission.Default.FALSE
        }

        register("cult.verbose") {
            description = "Receive verbose alerts for violations"
            default = Permission.Default.OP
        }

        register("cult.verbose.enable-on-join") {
            description =
                "Enable verbose alerts on join"
            default = Permission.Default.FALSE
        }

        register("cult.list") {
            description =
                "Shows lists of specific data"
            default = Permission.Default.FALSE
        }

    }
}

publishing.publications.create<MavenPublication>("maven") {
    artifact(tasks["shadowJar"])
}

tasks {
    // 1.8.8 - 1.16.5   = Java 8
    // 1.17             = Java 16
    // 1.18 - 1.20.4    = Java 17
    // 1.20.5 - 1.21.11 = Java 21
    // 26.1+            = Java 25
    val version = "26.3"
    val javaVersion = JavaLanguageVersion.of(25)

    val jvmArgsExternal = listOf(
        "-Dcom.mojang.eula.agree=true",
        "-Dpaper.explicit-flush=true",
        "-DPaper.IgnoreJavaVersion=true"
    )

    runServer {
        minecraftVersion(version)
        runDirectory = projectDir.resolve("run/$version")

        val javaToolchains = project.extensions.getByType<JavaToolchainService>()
        javaLauncher = javaToolchains.launcherFor {
            vendor = JvmVendorSpec.JETBRAINS
            languageVersion = javaVersion
        }

        jvmArgs = jvmArgsExternal
    }

    shadowJar {
        minimize {
            // Keep the complete protocol catalog and transport handlers.
            exclude(project(":protocol"))
            exclude(project(":protocol-netty"))
            // The library is already filtered and relocated before compilation.
            exclude(project(":protocol-codec"))
        }

        exclude("META-INF/services/javax.annotation.processing.Processor")

        manifest {
            attributes["paperweight-mappings-namespace"] = "mojang"
        }
    }
}

// Development fat jar for the local smoketest harness
// (scripts/run-local-all-smoketests.sh expects `devShadowJar` to produce a
// *-dev.jar under the repo root build/libs). Unlike shadowJar this bundles the
// runtime classpath unminimized and without relocations, so validation never
// depends on minimize/relocate correctness.
tasks.register<ShadowJar>("devShadowJar") {
    group = "shadow"
    description = "Builds a development fat jar without relocations."

    from(sourceSets["main"].output)
    configurations = listOf(project.configurations["runtimeClasspath"])

    archiveFileName.set("CultAC-dev.jar")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("libs"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/services/javax.annotation.processing.Processor")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    mergeServiceFiles()

    manifest {
        attributes["paperweight-mappings-namespace"] = "mojang"
    }
}

// Inspect the actual output, including transitively shaded and relocated entries.
// Native conformance sources and migration archives never enter the runtime jar.
tasks.withType<ShadowJar>().configureEach {
    doLast {
        check(ProcessBuilder("python3", rootProject.file("scripts/verify-no-bundled-minecraft.py").path,
            archiveFile.get().asFile.path).inheritIO().start().waitFor() == 0)
        val codecAudit = ProcessBuilder("python3", rootProject.file("scripts/verify-vialib-packaging.py").path,
            "--library", rootProject.project(":protocol-codec").layout.buildDirectory.file("libs/vialib.jar").get().asFile.path,
            archiveFile.get().asFile.path).redirectErrorStream(true).start()
        val codecAuditOutput = codecAudit.inputStream.bufferedReader().use { it.readText() }
        check(codecAudit.waitFor() == 0) { codecAuditOutput }
        logger.lifecycle(codecAuditOutput.trim())
        val forbiddenOracle = Regex("protocol/(validation|oracle|legacy)/|protocolvalidation/|protocol[-_]oracle|protocol[-_]validation|legacy[-_.]?extractor", RegexOption.IGNORE_CASE)
        ZipFile(archiveFile.get().asFile).use { archive ->
            for (required in listOf(
                "ac/cult/cultac/protocol/ProtocolRuntime.class",
                "ac/cult/cultac/network/CultConnection.class",
                "ac/cult/cultac/protocol/netty/CultDecoder.class",
                "ac/cult/cultac/protocol/netty/CultEncoder.class"
            )) {
                check(archive.getEntry(required) != null) { "Protocol class missing from production jar: $required" }
            }
            for (entry in archive.entries()) {
                check(!forbiddenOracle.containsMatchIn(entry.name)) { "Protocol oracle in production jar: ${entry.name}" }
                if (entry.name.endsWith("plugin.yml") || entry.name.endsWith("paper-plugin.yml")) {
                    val descriptor = archive.getInputStream(entry).bufferedReader().use { it.readText() }
                    check(!Regex("protocol[. /_-]*(validation|oracle)|legacy[. /_-]*extractor", RegexOption.IGNORE_CASE)
                        .containsMatchIn(descriptor)) { "Protocol oracle descriptor in production jar: ${entry.name}" }
                }
            }
        }
    }
}
