import net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import versioning.BuildConfig

plugins {
    cult.`base-conventions`
    id("com.gradleup.shadow")
    id("de.eldoria.plugin-yml.bukkit") version "0.8.0"
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


java.toolchain.languageVersion.set(JavaLanguageVersion.of(25))
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }
configurations.configureEach { attributes.attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25) }
sourceSets.main { java.setSrcDirs(listOf("src/loader/java")) }
dependencies {
    paperweight.paperDevBundle(providers.gradleProperty("paperDevBundleVersion").get())
    implementation(project(":reload-host"))
    compileOnly(libs.placeholderapi)
    implementation(libs.cult.bukkit.internal)
}

bukkit {
    name = "CultAC"
    provides = listOf("GrimAC")
    author = "CultAC"
    main = "ac.cult.loader.bukkit.CultACBootstrapPlugin"
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
        register("cult.update") { default = Permission.Default.OP }
        register("cult.runtimes") { default = Permission.Default.OP }
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

        register("cult.cloud") {
            description = "View the cloud upload's status"
            default = Permission.Default.OP
        }

        register("cult.cloud.view") {
            description = "View cloud findings"
            default = Permission.Default.OP
        }

        register("cult.cloud.label") {
            description = "Label players in the cloud"
            default = Permission.Default.OP
        }

        register("cult.cloud.label.clean") {
            description = "Offer Clean in the label menu"
            default = Permission.Default.OP
        }

        register("cult.portal.admin") {
            description = "Reported to the cloud portal as an admin of this server's organization"
            default = Permission.Default.FALSE
        }

        register("cult.portal.moderator") {
            description = "Reported to the cloud portal as a moderator of this server's organization"
            default = Permission.Default.FALSE
        }

        register("cult.portal.viewer") {
            description = "Reported to the cloud portal as a viewer of this server's organization"
            default = Permission.Default.FALSE
        }

    }
}

evaluationDependsOn(":bukkit-runtime")
val engine = project(":bukkit-runtime").tasks.named<ShadowJar>("shadowJar")
tasks.shadowJar {
    archiveFileName.set("CultAC-paper.jar")
    dependsOn(engine)
    from(engine.flatMap { it.archiveFile }) { into("cult-runtime"); rename { "default.jar" } }
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    manifest.attributes["paperweight-mappings-namespace"] = "mojang"

}
tasks.register<ShadowJar>("devShadowJar") {
    from(sourceSets.main.get().output)
    configurations = listOf(project.configurations["runtimeClasspath"])
    dependsOn(engine)
    from(engine.flatMap { it.archiveFile }) { into("cult-runtime"); rename { "default.jar" } }
    archiveFileName.set("CultAC-dev.jar")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("libs"))
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    manifest.attributes["paperweight-mappings-namespace"] = "mojang"

}
tasks.assemble { dependsOn(tasks.shadowJar) }


tasks.processResources {
    val loaderVersion = providers.gradleProperty("cultLoaderVersion").get()
    inputs.property("cult-loader-version", loaderVersion)
    filesMatching("plugin.yml") {
        filter { line: String -> if (line.startsWith("version:")) "$line\ncult-loader-version: '$loaderVersion'" else line }
    }
}
