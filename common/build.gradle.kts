import versioning.BuildConfig

plugins {
    `maven-publish`
    cult.`base-conventions`
    id("io.papermc.paperweight.userdev")
}

repositories {
    val localOverride = if (BuildConfig.mavenLocalOverride) mavenLocal() else null

    // Cult API
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

    // ViaVersion
    exclusive("https://repo.viaversion.com", { mavenContent { releasesOnly() } }) {
        includeGroup("com.viaversion")
    }

    // Configuralize
    exclusive("https://nexus.scarsz.me/content/repositories/releases", { mavenContent { releasesOnly() } }) {
        includeGroup("github.scarsz")
    }

    // Geyser ecosystem. Plain (non-exclusive) repositories: Geyser core drags in a
    // snapshots/releases mix of org.geysermc.*/org.cloudburstmc.*/com.nukkitx.* artifacts,
    // which exclusiveContent's first-match-wins group filtering cannot express.
    maven("https://repo.opencollab.dev/maven-releases/")
    maven("https://repo.opencollab.dev/maven-snapshots/")

    mavenCentral()
}

// The current Paper API is built for Java 25, but Cult's classes target Java 21 so
// final Paper releases across the declared 1.21+ server range can parse the jar. Resolve the
// compile classpath using the API's runtime level without raising Cult's emitted bytecode.
configurations.configureEach {
    attributes.attribute(
        org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE,
        25
    )
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

val bedrockMovementCollisionOverridesResource = layout.projectDirectory.file(
    "src/main/resources/bedrock/cultac-bedrock-collision-overrides.json.gz"
)

// Keep the generator on its own Gradle wrapper; it uses a different plugin stack.
val mappingsGeneratorDirectory = rootProject.layout.projectDirectory.dir("mappings-generator")
val bedrockMovementCollisionCatalog = mappingsGeneratorDirectory.file(
    "build/generated/cultac-bedrock-collision-overrides.json.gz"
)
val generateBedrockMovementCollisionOverrides = tasks.register<Exec>("generateBedrockMovementCollisionOverrides") {
    group = "generation"
    description = "Regenerates the checked-in Bedrock collision catalog (Linux x86-64 only)."
    workingDir(mappingsGeneratorDirectory)
    commandLine("./gradlew", "generateCultacBedrockCollisionOverrides", "--no-daemon", "--console=plain")
    doFirst {
        if (System.getProperty("os.name") != "Linux" || System.getProperty("os.arch") !in setOf("amd64", "x86_64")) {
            throw GradleException(
                "Geometry regeneration requires Linux x86-64. Normal builds use the checked-in catalog; run `build` instead."
            )
        }
        if (!mappingsGeneratorDirectory.file("gradlew").asFile.isFile) {
            throw GradleException(
                "Mappings generator submodule is missing. Run `git submodule update --init --recursive` from the repository root."
            )
        }
    }
    doLast {
        check(bedrockMovementCollisionCatalog.asFile.isFile) {
            "Mappings generator did not produce ${bedrockMovementCollisionCatalog.asFile}."
        }
        copy {
            from(bedrockMovementCollisionCatalog)
            into(bedrockMovementCollisionOverridesResource.asFile.parentFile)
        }
    }
}

tasks.processResources {
    // An explicit required input prevents accidentally shipping a jar without geometry.
    inputs.file(bedrockMovementCollisionOverridesResource).withPropertyName("bedrockCollisionCatalog")
    // Allows an explicit regeneration and build in one invocation without making
    // regeneration part of the normal build graph.
    mustRunAfter(generateBedrockMovementCollisionOverrides)
}

dependencies {
    paperweight.paperDevBundle(providers.gradleProperty("paperDevBundleVersion").get())

    api(libs.cloud.core)
    api(libs.cloud.processors.requirements)
    api(libs.configuralize) {
        artifact {
            classifier = "slim"
        }
        exclude(group = "org.yaml", module = "snakeyaml")
    }
    // Bump snakeyaml (transitive dep of configuralize) 1.29 -> 2.2+ for geyser-fabric
    api(libs.snakeyaml)
    // Paper/Minecraft provides fastutil. Keeping it off the runtime classpath is
    // also required for NMS method descriptors that expose fastutil types.
    compileOnly(libs.fastutil)
    api(libs.adventure.text.minimessage)
    api(libs.jetbrains.annotations)
    api(libs.hikaricp)
    api(libs.cult.api)
    api(libs.cult.internal)
    compileOnly(libs.cult.internal.shims)
    compileOnly(libs.mongoDriverSync)

    compileOnly(libs.geyser.base.api) {
        isTransitive = false // messes with guava otherwise
    }
    compileOnly(libs.geyser.core)

    compileOnly(libs.floodgate.api)
    compileOnly(libs.viaversion)
    compileOnly(libs.viabackwards)
    compileOnly(libs.netty)
    compileOnly(libs.luckperms)

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(libs.fastutil)


    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.4")
    testImplementation("org.powermock:powermock-core:2.0.9")
    testImplementation("org.powermock:powermock-api-mockito2:2.0.9")
    testImplementation("org.powermock:powermock-module-junit4:2.0.9")
    testImplementation("com.google.code.gson:gson:2.13.2")
    testImplementation("com.google.guava:guava:31.1-jre")
    testImplementation(libs.netty)
    testImplementation(libs.cult.api)
    testCompileOnly(libs.geyser.core)
    testRuntimeOnly(libs.geyser.core)
    // CultPlayer declares a ViaVersion PacketTracker field (compileOnly in main); tests
    // reflect over CultPlayer's declared fields, which loads every field type.
    testCompileOnly(libs.viaversion)
    testRuntimeOnly(libs.viaversion)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
    System.getProperty("exportBedrockFixtures")?.let {
        systemProperty("exportBedrockFixtures", it)
    }
    // Opt-in tests against the unmodified GFP jar; PowerMock needs these opens on Java 25.
    System.getProperty("gfpTestJar")?.let {
        systemProperty("gfpTestJar", it)
        jvmArgs(
            "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED",
            "--add-opens=java.base/java.net=ALL-UNNAMED",
            "--add-opens=java.base/java.nio.file=ALL-UNNAMED"
        )
    }
    include("**/*Test.class")
    exclude("**/*$*")
}

tasks.register<Test>("offlineBedrockReplayTest") {
    group = "verification"
    description = "Runs the fast offline Bedrock replay harness tests without launching Paper, Geyser, or Floodgate."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    maxHeapSize = "2g"
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
    System.getProperty("exportBedrockFixtures")?.let {
        systemProperty("exportBedrockFixtures", it)
    }
    System.getProperty("bedrockReplayScenarios")?.let {
        systemProperty("bedrockReplayScenarios", it)
    }
    System.getProperty("bedrockReplayCheats")?.let {
        systemProperty("bedrockReplayCheats", it)
    }
    include("**/bedrock/replay/offline/**/*Test.class")
    exclude("**/*$*")
}

publishing.publications.create<MavenPublication>("maven") {
    from(components["java"])
}
