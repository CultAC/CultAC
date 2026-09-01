import versioning.BuildConfig

plugins {
    `maven-publish`
    grim.`base-conventions`
    id("io.papermc.paperweight.userdev")
}

repositories {
    val localOverride = if (BuildConfig.mavenLocalOverride) mavenLocal() else null

    // Grim API
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

// The current Paper API is built for Java 25, but Grim's classes target Java 21 so
// final Paper releases across the declared 1.21+ server range can parse the jar. Resolve the
// compile classpath using the API's runtime level without raising Grim's emitted bytecode.
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

val bedrockMovementCollisionOverridesResource = layout.buildDirectory.file(
    "generated-resources/bedrock-movement/bedrock/cultac-bedrock-collision-overrides.json.gz"
)

val prepareBedrockMovementCollisionOverrides = tasks.register("prepareBedrockMovementCollisionOverrides") {
    group = "build"
    description = "Bundles the generated sparse Bedrock collision overrides used by Bedrock movement validation."
    val candidates = listOf(
        layout.projectDirectory.file("../mappings-generator/build/generated/cultac-bedrock-collision-overrides.json.gz"),
        layout.projectDirectory.file("mappings-generator/build/generated/cultac-bedrock-collision-overrides.json.gz"),
        layout.projectDirectory.file("../bedrock-movement-smoketest/build/generated/bedrock/cultac-bedrock-collision-overrides.json.gz"),
        rootProject.layout.projectDirectory.file("../CultAC/mappings-generator/build/generated/cultac-bedrock-collision-overrides.json.gz")
    )
    inputs.files(candidates)
    outputs.file(bedrockMovementCollisionOverridesResource)

    doLast {
        val source = candidates
            .map { it.asFile }
            .firstOrNull { it.isFile }
            ?: throw GradleException(
                "Generated Bedrock collision override catalog not found. Run collision override generation first, "
                    + "for example `cd ../mappings-generator && "
                    + "./gradlew generateCultacBedrockCollisionOverrides`. Checked: "
                    + candidates.joinToString { it.asFile.absolutePath }
            )
        copy {
            from(source)
            into(bedrockMovementCollisionOverridesResource.get().asFile.parentFile)
            rename { "cultac-bedrock-collision-overrides.json.gz" }
        }
    }
}

tasks.processResources {
    dependsOn(prepareBedrockMovementCollisionOverrides)
    from(bedrockMovementCollisionOverridesResource) {
        into("bedrock")
    }
}

dependencies {
    paperweight.paperDevBundle("26.2.build.112-stable")

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
    api(libs.grim.api)
    api(libs.grim.internal)
    compileOnly(libs.grim.internal.shims)
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
    testImplementation(libs.grim.api)
    testCompileOnly(libs.geyser.core)
    testRuntimeOnly(libs.geyser.core)
    // GrimPlayer declares a ViaVersion PacketTracker field (compileOnly in main); tests
    // reflect over GrimPlayer's declared fields, which loads every field type.
    testCompileOnly(libs.viaversion)
    testRuntimeOnly(libs.viaversion)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
    System.getProperty("exportBedrockFixtures")?.let {
        systemProperty("exportBedrockFixtures", it)
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
