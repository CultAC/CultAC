plugins {
    java
    id("io.papermc.paperweight.userdev")
}

// Isolated compilation of the legacy block-placement logic against the Paper 1.21.3 API
// surface. Classes are merged into the bukkit shadow jar (see bukkit/build.gradle.kts).
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}

dependencies {
    paperweight.paperDevBundle("1.21.3-R0.1-SNAPSHOT")
    compileOnly("org.jetbrains:annotations:24.1.0")
}

sourceSets.main {
    java.srcDir(rootProject.file("src/legacy/java"))
}

tasks.compileJava {
    dependsOn(":common:classes")
    classpath += files(project(":common").layout.buildDirectory.dir("classes/java/main"))
    options.release.set(21)
    options.encoding = "UTF-8"
}
