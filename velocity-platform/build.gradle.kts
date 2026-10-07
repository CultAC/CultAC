plugins { cult.`base-conventions` }

repositories {
    if (providers.gradleProperty("mavenLocalOverride").getOrElse("false").toBoolean()) mavenLocal()
    maven("https://maven.grim.ac/public/releases")
    maven("https://maven.grim.ac/public/snapshots")
    maven("https://repo.grim.ac/snapshots")
    maven("https://nexus.scarsz.me/content/repositories/releases")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.viaversion.com") { content { includeGroup("com.viaversion") } }
    mavenCentral()
}
java.toolchain.languageVersion.set(JavaLanguageVersion.of(25))
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }
configurations.configureEach {
    attributes.attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
}
dependencies {
    api(project(":common"))
    implementation(project(":protocol-netty"))
    implementation(libs.cult.internal.shims)
    implementation("org.incendo:cloud-velocity:2.0.0-beta.10")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    compileOnly("com.velocitypowered:velocity-api:3.4.0")
    compileOnly("io.netty:netty-handler:4.2.18.Final")
    testImplementation("com.velocitypowered:velocity-api:3.4.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.mockito:mockito-core:5.22.0")
    testImplementation("io.netty:netty-handler:4.2.18.Final")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(libs.viaversion)
}
tasks.test {
    useJUnitPlatform()
}

// The proxy engine's classes target Java 21, including its published variants.
for (variant in listOf("apiElements", "runtimeElements")) {
    configurations.named(variant) {
        attributes.attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
    }
}
