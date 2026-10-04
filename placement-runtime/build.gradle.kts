plugins { `java-library`; cult.`format-conventions` }

repositories { mavenCentral() }
dependencies {
    api(project(":placement-api"))
    api(project(":vanilla-bootstrap"))
    // Shrinks the isolated vanilla classes as they load (VanillaClassTransform).
    implementation("org.ow2.asm:asm:9.9.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.jar)
    systemProperty("placementRuntimeJar", tasks.jar.flatMap { it.archiveFile }.get().asFile.absolutePath)
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
tasks.compileJava { options.release.set(21) }

val classDefinerJar = tasks.register<Jar>("classDefinerJar") {
    from(tasks.compileJava.flatMap { it.destinationDirectory }) {
        include("ac/cult/placement/runtime/ClassDefiner.class")
    }
    archiveFileName.set("cult-class-definer.jar")
    destinationDirectory.set(layout.buildDirectory.dir("owned-runtime"))
}

// Only our bridge bytecode is packaged. The original named server is a verified
// compile input here and is acquired independently by the plugin at runtime.
val modelAcquisition = configurations.create("modelAcquisition") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies.add(modelAcquisition.name, project(":vanilla-bootstrap"))
val modelCache = layout.buildDirectory.dir("runtime-models")
val prepareEarlierModel = tasks.register<JavaExec>("prepareEarlierModel") {
    dependsOn(":vanilla-bootstrap:jar")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    classpath = modelAcquisition
    mainClass.set("ac.cult.runtime.AcquireServer")
    inputs.file(project(":vanilla-runtime").file("1.21.11-lock.json"))
    outputs.dir(modelCache.map { it.dir("1.21.11") })
    args("1.21.11", modelCache.get().asFile.absolutePath)
}
val earlierInteraction = sourceSets.create("interaction1_21_11") {
    java.setSrcDirs(listOf("src/interaction/java", "src/model1_21_11/java"))
    compileClasspath += configurations.compileClasspath.get()
    compileClasspath += files(tasks.compileJava.flatMap { it.destinationDirectory })
    compileClasspath += files(modelCache.map { it.file("1.21.11/server-model.jar") })
    compileClasspath += fileTree(modelCache.map { it.dir("1.21.11/server-lib") }) { include("**/*.jar") }
}
tasks.named<JavaCompile>(earlierInteraction.compileJavaTaskName) {
    dependsOn(prepareEarlierModel)
    options.release.set(21)
}
tasks.processResources {
    from(earlierInteraction.output) { into("placement-models/1.21.11") }
    from(classDefinerJar) { into("runtime") }
}

// Interaction semantics compile against the same verified official 26.3 server that
// the plugin acquires at runtime; the model itself is never packaged.
val prepareModel = tasks.register<JavaExec>("prepareModel") {
    dependsOn(":vanilla-bootstrap:jar")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    classpath = modelAcquisition
    mainClass.set("ac.cult.runtime.AcquireServer")
    inputs.file(project(":vanilla-runtime").file("26.3-lock.json"))
    outputs.dir(modelCache.map { it.dir("26.3") })
    args("26.3", modelCache.get().asFile.absolutePath)
}
val interaction = sourceSets.create("interaction") {
    java.srcDir("src/model26_3/java")
    compileClasspath += configurations.compileClasspath.get()
    compileClasspath += files(tasks.compileJava.flatMap { it.destinationDirectory })
    compileClasspath += files(modelCache.map { it.file("26.3/server-model.jar") })
    compileClasspath += fileTree(modelCache.map { it.dir("26.3/server-lib") }) { include("**/*.jar") }
}
tasks.named<JavaCompile>(interaction.compileJavaTaskName) {
    dependsOn(prepareModel)
    options.release.set(21)
}
tasks.jar { from(interaction.output) }

// Fail the build if a future dependency or resource copy reintroduces Minecraft.
tasks.jar {
    doLast {
        check(ProcessBuilder("python3", rootProject.file("scripts/verify-no-bundled-minecraft.py").path,
            archiveFile.get().asFile.path).inheritIO().start().waitFor() == 0)
    }
}
