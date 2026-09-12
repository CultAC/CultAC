plugins {
    java
    id("io.papermc.paperweight.userdev")
}

// Level removed brewing/fuel APIs in RC1. Keep those descriptors isolated so
// reflection on the RC1 placement world never loads removed NMS classes.
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
    paperweight.paperDevBundle("26.2.build.112-stable")
    compileOnly("org.jetbrains:annotations:24.1.0")
}
tasks.compileJava {
    dependsOn(":common:classes")
    classpath += files(project(":common").layout.buildDirectory.dir("classes/java/main"))
    options.release.set(21)
    options.encoding = "UTF-8"
}
