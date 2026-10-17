import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import versioning.BuildConfig
import java.util.zip.ZipFile

plugins {
    cult.`base-conventions`
    id("com.gradleup.shadow")
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
    implementation(libs.cult.bukkit.internal)

    implementation(project(":common"))
    implementation(project(":protocol-netty"))
    compileOnly(project(":reload-host"))
}

sourceSets.main { java.srcDir(rootProject.file("bukkit/src/main/java")) }
val runtimeDescriptor = tasks.register("runtimeDescriptor") {
    val descriptor = layout.buildDirectory.file("runtime-descriptor/plugin.yml")
    inputs.property("version", rootProject.version.toString())
    inputs.property("loader-version", providers.gradleProperty("cultLoaderVersion").get())
    outputs.file(descriptor)
    doLast {
        descriptor.get().asFile.apply { parentFile.mkdirs(); writeText("name: CultAC\nversion: '${rootProject.version}'\ncult-loader-version: '${providers.gradleProperty("cultLoaderVersion").get()}'\n") }
    }
}
tasks.shadowJar {
    dependsOn(runtimeDescriptor)
    from(layout.buildDirectory.dir("runtime-descriptor"))
    archiveFileName.set("CultAC-paper-runtime.jar")
    dependencies {
        exclude(dependency("ac.grim.grimac:.*:.*"))
        exclude(dependency("com.zaxxer:HikariCP:.*"))
        exclude(dependency("com.google.code.gson:gson:.*"))
        exclude(dependency("com.google.guava:guava:.*"))
        exclude(dependency("it.unimi.dsi:fastutil:.*"))
        exclude(dependency("org.joml:joml:.*"))
        exclude(dependency("org.xerial:sqlite-jdbc:.*"))
        exclude(dependency("org.mongodb:.*:.*"))
        exclude(project(":reload-api"))
        exclude(project(":reload-host"))
        exclude(project(":reload-services"))
        exclude(project(":reload-integrations"))
    }
    relocate("com.google.protobuf", "ac.cult.cultac.libs.protobuf")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("META-INF/services/javax.annotation.processing.Processor")
    excludeCloudExtras()
    mergeServiceFiles()
    manifest.attributes(mapOf(
        "paperweight-mappings-namespace" to "mojang",
        "Cult-Runtime-SPI" to "1",
        "Cult-Runtime-Platform" to "paper",
        "Cult-Runtime-Java" to "21",
        "Cult-Runtime-Host-Version" to providers.gradleProperty("paperDevBundleVersion").get().substringBefore(".build."),
        "Cult-Runtime-Build" to rootProject.version.toString(),
        "Cult-Runtime-Entrypoint" to "ac.cult.cultac.platform.bukkit.PaperRuntimeEntrypoint"
    ))
    doLast {
        check(ProcessBuilder("python3", rootProject.file("scripts/verify-no-bundled-minecraft.py").path,
            archiveFile.get().asFile.path).inheritIO().start().waitFor() == 0)
        check(ProcessBuilder("python3", rootProject.file("scripts/verify-vialib-packaging.py").path,
            "--library", rootProject.project(":protocol-codec").layout.buildDirectory.file("libs/vialib.jar").get().asFile.path,
            archiveFile.get().asFile.path).inheritIO().start().waitFor() == 0)
        verifyCloudRuntimeJar(archiveFile.get().asFile)
    }
}
tasks.assemble { dependsOn(tasks.shadowJar) }
