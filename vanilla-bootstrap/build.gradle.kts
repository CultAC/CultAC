plugins { `java-library`; cult.`format-conventions` }

repositories { mavenCentral() }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }
dependencies {
    api("org.ow2.asm:asm:9.9.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test { useJUnitPlatform() }

// Only acquisition metadata goes into the plugin, never Minecraft archives.
for (version in listOf("1.21.11", "26.3")) {
    val generateLock = tasks.register<Exec>("generateLock" + version.replace(".", "_")) {
        val lock = project(":vanilla-runtime").file("$version-lock.json")
        val script = file("write-lock.py")
        val output = layout.buildDirectory.file("acquisition-resources/vanilla/$version.properties")
        inputs.files(lock, script)
        outputs.file(output)
        commandLine("python3", script, lock, output.get().asFile)
    }
    tasks.processResources { dependsOn(generateLock) }
}
tasks.processResources { from(layout.buildDirectory.dir("acquisition-resources")) }
