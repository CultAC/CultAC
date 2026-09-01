import net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import versioning.BuildConfig

plugins {
    `maven-publish`
    grim.`base-conventions`
    grim.`shadow-conventions`
    id("de.eldoria.plugin-yml.bukkit") version "0.8.0"
    id("xyz.jpenilla.run-paper") version "3.0.0-beta.1"
    id("io.papermc.paperweight.userdev")
}

repositories {
    val localOverride = if (BuildConfig.mavenLocalOverride) mavenLocal() else null

    // Exclusive Repositories (One HTTP request per dep)
    exclusive("https://repo.papermc.io/repository/maven-public/", { name = "papermc" }) {
        includeGroup("io.papermc.paper")
        includeGroup("net.md-5")
    }

    exclusive("https://libraries.minecraft.net", { mavenContent { releasesOnly() } }) {
        includeModule("com.mojang", "brigadier")
    }

    exclusive("https://repo.extendedclip.com/content/repositories/placeholderapi/") {
        includeGroup("me.clip")
    }

    val grimPublicReleases = maven("https://maven.grim.ac/public/releases") {
        mavenContent { releasesOnly() }
    }
    val grimPublicSnapshots = maven("https://maven.grim.ac/public/snapshots") {
        mavenContent { snapshotsOnly() }
    }
    val grimLegacySnapshots = maven("https://repo.grim.ac/snapshots")
    exclusiveContent {
        forRepositories(*listOfNotNull(localOverride, grimPublicReleases, grimPublicSnapshots, grimLegacySnapshots).toTypedArray())
        filter {
            includeGroup("ac.grim.grimac")
        }
    }

    exclusive("https://nexus.scarsz.me/content/repositories/releases", { mavenContent { releasesOnly() } }) {
        includeGroup("github.scarsz")
    }

    mavenCentral()
}

// See common/build.gradle.kts: compile against the Java 25 Paper runtime level while
// emitting Java 21 bytecode so older supported servers can parse the jar.
configurations.configureEach {
    attributes.attribute(
        org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE,
        25
    )
}

// paperweight-userdev on :common exposes several extra consumable variants.
// Without attributes, `shadow(project(":common"))` is ambiguous;
// pin the standard library attributes so it resolves to :common's runtimeElements jar.
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
    paperweight.paperDevBundle("26.2.build.112-stable")

    compileOnly(libs.placeholderapi)
    compileOnly(libs.luckperms)

    implementation(libs.cloud.paper)
    implementation(libs.adventure.platform.bukkit)
    implementation(libs.grim.bukkit.internal)

    implementation(project(":common"))
    shadow(project(":common"))
}

bukkit {
    name = "GrimAC"
    author = "GrimAC"
    main = "ac.grim.grimac.platform.bukkit.GrimACBukkitLoaderPlugin"
    website = "https://grim.ac/"
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
        register("grim.alerts") {
            description = "Receive alerts for violations"
            default = Permission.Default.OP
        }

        register("grim.alerts.enable-on-join") {
            description = "Enable alerts on join"
            default = Permission.Default.OP
        }

        register("grim.performance") {
            description = "Check performance metrics"
            default = Permission.Default.OP
        }

        register("grim.profile") {
            description = "Check user profile"
            default = Permission.Default.OP
        }

        register("grim.brand") {
            description = "Show client brands on join"
            default = Permission.Default.OP
        }

        register("grim.brand.enable-on-join") {
            description = "Enable showing client brands on join"
            default = Permission.Default.OP
        }

        register("grim.sendalert") {
            description = "Send cheater alert"
            default = Permission.Default.OP
        }

        register("grim.nosetback") {
            description = "Disable setback"
            default = Permission.Default.FALSE
        }

        register("grim.nomodifypacket") {
            description = "Disable modifying packets"
            default = Permission.Default.FALSE
        }

        register("grim.disabled") {
            description = "Disable Grim checks while keeping player state tracked"
            default = Permission.Default.FALSE
        }

        register("grim.exempt") {
            description = "Exempt from all checks"
            default = Permission.Default.FALSE
        }

        register("grim.verbose") {
            description = "Receive verbose alerts for violations"
            default = Permission.Default.OP
        }

        register("grim.verbose.enable-on-join") {
            description =
                "Enable verbose alerts on join"
            default = Permission.Default.FALSE
        }

        register("grim.list") {
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
    val version = "26.2"
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
        dependsOn(":legacy-placement-adapter:classes")
        from(project(":legacy-placement-adapter").layout.buildDirectory.dir("classes/java/main"))

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

    dependsOn(":legacy-placement-adapter:classes")
    from(sourceSets["main"].output)
    from(project(":legacy-placement-adapter").layout.buildDirectory.dir("classes/java/main"))
    configurations = listOf(project.configurations["runtimeClasspath"])

    archiveFileName.set("grimac-${rootProject.version}-dev.jar")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("libs"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/services/javax.annotation.processing.Processor")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    mergeServiceFiles()

    manifest {
        attributes["paperweight-mappings-namespace"] = "mojang"
    }
}
