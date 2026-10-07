plugins {
    cult.`base-conventions`
}

repositories {
    maven("https://repo.viaversion.com") { content { includeGroup("com.viaversion") } }
    mavenCentral()
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(25))
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }

// Keep the shared toolchain/style conventions without its Lombok classpath.
configurations.configureEach {
    setExtendsFrom(extendsFrom.filterNot { it.name == "lombok" })
}

dependencies {
    implementation(project(mapOf("path" to ":protocol-codec", "configuration" to "shadowRuntimeElements")))
    compileOnly("io.netty:netty-transport:4.2.16.Final")
    compileOnly("io.netty:netty-codec-base:4.2.16.Final")
    compileOnly("com.google.guava:guava:33.5.0-jre")
    // Paper 26.3; only the ByteBuf API is used, also provided by older Papers.
    compileOnly("io.netty:netty-buffer:4.2.16.Final")
    testImplementation("io.netty:netty-buffer:4.2.16.Final")
    testRuntimeOnly("io.netty:netty-transport:4.2.16.Final")
    testRuntimeOnly("io.netty:netty-codec-base:4.2.16.Final")
    testRuntimeOnly("com.google.guava:guava:33.5.0-jre")
    // Exercise coexistence with the installed translator's original API/resources.
    testImplementation(libs.via.codecs.core) { isTransitive = false }
    testImplementation(testlibs.junitJupiter)
    testRuntimeOnly(testlibs.junitPlatformLauncher)
}

tasks.test {
    useJUnitPlatform()
    val fixtures = layout.buildDirectory.dir("test-fixtures")
    outputs.dir(fixtures)
    systemProperty("wireValueFixtures", fixtures.get().asFile.absolutePath)
}

sourceSets.test { java.srcDir("src/fixtures/java") }
