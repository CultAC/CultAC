plugins {
    `kotlin-dsl`
    kotlin("plugin.serialization") version "2.3.0" // Use the same Kotlin version as your main project
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/") {
        content { includeGroup("net.fabricmc") }
    }
}

dependencies {
    implementation(libs.spotless)
    implementation(libs.lombok)
    implementation(libs.shadow)
    implementation("org.ow2.asm:asm:9.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    // The mcsrc task: remaps obfuscated jars with Mojang's mappings, then forks Vineflower.
    implementation(libs.tiny.remapper)
    implementation(libs.mapping.io)
    implementation(libs.vineflower)
}
