plugins {
    java
    cult.`format-conventions`
    id("com.gradleup.shadow")
    id("io.papermc.paperweight.userdev")
}

repositories {
    if (providers.gradleProperty("mavenLocalOverride").getOrElse("false").toBoolean()) mavenLocal()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.grim.ac/public/releases")
    maven("https://maven.grim.ac/public/snapshots")
    maven("https://repo.grim.ac/snapshots")
    mavenCentral()
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(25))
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

// Compile the host boundary against Paper's Java 25 API while emitting Java 21 bytecode.
configurations.configureEach {
    attributes.attribute(
        org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE,
        25
    )
}

dependencies {
    paperweight.paperDevBundle(providers.gradleProperty("paperDevBundleVersion").get())

    implementation(project(":bridge-protocol"))
    implementation(libs.cult.api)
    implementation(libs.cult.internal) { isTransitive = false }
    implementation(libs.cult.bukkit.internal) { isTransitive = false }

    testImplementation(testlibs.junitJupiter)
    testRuntimeOnly(testlibs.junitPlatformLauncher)
}

tasks.test { useJUnitPlatform() }

tasks.processResources {
    val version = rootProject.version.toString()
    inputs.property("version", version)
    filesMatching("plugin.yml") { expand("version" to version) }
}

tasks.shadowJar {
    archiveFileName.set("CultAC-companion.jar")
    // Only the event bus and extension resolver of grim-internal belong on a backend.
    exclude("ac/grim/grimac/internal/storage/**", "META-INF/grim/**")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    manifest {
        attributes["paperweight-mappings-namespace"] = "mojang"
    }
    doLast {
        check(ProcessBuilder("python3", rootProject.file("scripts/verify-no-bundled-minecraft.py").path,
            archiveFile.get().asFile.path).inheritIO().start().waitFor() == 0)
    }
}

tasks.assemble { dependsOn(tasks.shadowJar) }
