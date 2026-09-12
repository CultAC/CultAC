plugins {
    java
    id("io.papermc.paperweight.userdev")
}

// Keep the pre-clock Level ABI isolated while retaining native block-use prediction.
java.toolchain.languageVersion.set(JavaLanguageVersion.of(25))
repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}
configurations.configureEach {
    attributes.attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
}

dependencies {
    compileOnly(platform("net.kyori:adventure-bom:4.26.1"))
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
    compileOnly("org.jetbrains:annotations:24.1.0")
}
tasks.compileJava {
    dependsOn(":common:classes")
    classpath += files(project(":common").layout.buildDirectory.dir("classes/java/main"))
    options.release.set(21)
    options.encoding = "UTF-8"
}
