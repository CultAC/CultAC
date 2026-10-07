plugins {
    cult.`base-conventions`
}

repositories {
    mavenLocal()
    maven("https://maven.grim.ac/public/releases")
    maven("https://maven.grim.ac/public/snapshots")
    maven("https://repo.grim.ac/snapshots")
    maven("https://nexus.scarsz.me/content/repositories/releases")
    mavenCentral()
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(25))
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }
configurations.matching { it.name.endsWith("CompileClasspath") || it.name.endsWith("RuntimeClasspath") || it.name in listOf("compileClasspath", "runtimeClasspath") }.configureEach {
    attributes.attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
}

configurations.configureEach {
    setExtendsFrom(extendsFrom.filterNot { it.name == "lombok" })
}

dependencies {
    api(project(":common"))
    compileOnly("io.netty:netty-transport:4.2.16.Final")
    compileOnly("io.netty:netty-codec-base:4.2.16.Final")
    testImplementation("io.netty:netty-transport:4.2.16.Final")
    testImplementation("io.netty:netty-codec-base:4.2.16.Final")
    testImplementation(testlibs.junitJupiter)
    testRuntimeOnly(testlibs.junitPlatformLauncher)
}

tasks.test { useJUnitPlatform() }

// Classes are emitted with --release 21 for consumers on older JVMs.
for (variant in listOf("apiElements", "runtimeElements")) {
    configurations.named(variant) {
        attributes.attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
    }
}
