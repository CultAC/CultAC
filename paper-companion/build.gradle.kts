import linkage.PaperLinkageCheck

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
    maven("https://repo.lunarclient.dev")
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
    // Present only on backends running Apollo-Bukkit; the apollo package loads only then.
    compileOnly(libs.apollo.api) { isTransitive = false }

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

// The companion runs on Paper 1.21.2 through the dev bundle version. -PpaperLinkageServer names a directory holding a
// Mojang-mapped server, e.g. a Paper 1.21.3 paperclip run with -Dpaperclip.patchonly=true (its versions/ and
// libraries/); without it the check is skipped.
val paperLinkageServer = providers.gradleProperty("paperLinkageServer")
val checkPaperLinkage = tasks.register<PaperLinkageCheck>("checkPaperLinkage") {
    group = "verification"
    description = "Fails on server references in the companion jar that do not link against the baseline server."
    onlyIf("-PpaperLinkageServer is set") { paperLinkageServer.isPresent }
    classes.from(tasks.shadowJar.flatMap { it.archiveFile })
    if (paperLinkageServer.isPresent) {
        baseline.from(fileTree(paperLinkageServer.get()) { include("versions/**/*.jar", "libraries/**/*.jar") })
    }
    packages.set(setOf(
        "net/minecraft/", "com/mojang/", "io/netty/", "net/kyori/adventure/",
        "io/papermc/paper/", "org/bukkit/", "com/destroystokyo/paper/"
    ))
    excluded.set(emptySet())
}
if (paperLinkageServer.isPresent) tasks.check { dependsOn(checkPaperLinkage) }
