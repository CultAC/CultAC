plugins { `java-library`; cult.`format-conventions` }
java.toolchain.languageVersion.set(JavaLanguageVersion.of(25))
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }
repositories {
    mavenLocal()
    maven("https://maven.grim.ac/public/releases")
    maven("https://maven.grim.ac/public/snapshots")
    maven("https://repo.grim.ac/snapshots")
    mavenCentral()
}
dependencies {
    compileOnly(libs.netty)
    implementation(libs.snakeyaml)
    implementation("com.google.code.gson:gson:2.11.0")
    api("com.google.guava:guava:33.5.0-jre")
    api("org.joml:joml:1.10.9")
    api(libs.fastutil)
    api(project(":reload-integrations"))
    api(project(":reload-services"))
    api(libs.cult.api)
    api(libs.cult.internal)
    api(libs.cult.bukkit.internal)
    api(project(":reload-api"))
    testImplementation(libs.cult.internal.shims)
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test { useJUnitPlatform() }
