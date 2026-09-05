plugins { java }

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.grim.ac/public/releases")
    maven("https://repo.grim.ac/snapshots")
}

dependencies {
    compileOnly("ac.grim.grimac:GrimAPI:1.6.0.9")
    compileOnly("ac.grim.grimac:grim-bukkit-internal:1.6.0.9")
    compileOnly("io.papermc.paper:paper-api:26.2.build.112-stable")
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }
tasks.jar { archiveFileName.set("GrimApiProbe.jar") }

// Paper 26.2 runs on Java 25; keep the probe's emitted bytecode at Java 21.
configurations.configureEach {
    attributes.attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
}
