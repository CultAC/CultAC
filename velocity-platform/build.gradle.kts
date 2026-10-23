import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins { cult.`base-conventions`; id("com.gradleup.shadow") }

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
    compileOnly(project(":reload-host"))
    api(project(":common"))
    implementation(project(":protocol-netty"))
    implementation(libs.cult.internal.shims)
    implementation("org.incendo:cloud-velocity:2.0.0-beta.10")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    compileOnly("com.velocitypowered:velocity-api:3.4.0")
    compileOnly(libs.luckperms)
    compileOnly("io.netty:netty-handler:4.2.18.Final")
    testImplementation("com.velocitypowered:velocity-api:3.4.0")
    testImplementation(project(":reload-api"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.mockito:mockito-core:5.22.0")
    testImplementation("io.netty:netty-handler:4.2.18.Final")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
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

val runtimeDescriptor = tasks.register("runtimeDescriptor") {
    val descriptor = layout.buildDirectory.file("runtime-descriptor/velocity-plugin.json")
    inputs.property("version", rootProject.version.toString())
    inputs.property("loader-version", providers.gradleProperty("cultLoaderVersion").get())
    outputs.file(descriptor)
    doLast {
        descriptor.get().asFile.apply { parentFile.mkdirs(); writeText("{\"id\":\"cultac\",\"version\":\"${rootProject.version}\",\"cult-loader-version\":\"${providers.gradleProperty("cultLoaderVersion").get()}\"}\n") }
    }
}
tasks.shadowJar {
    dependsOn(runtimeDescriptor)
    from(layout.buildDirectory.dir("runtime-descriptor"))
    archiveFileName.set("CultAC-velocity-runtime.jar")
    dependencies {
        exclude(dependency("ac.grim.grimac:.*:.*"))
        exclude(dependency("com.zaxxer:HikariCP:.*"))
        exclude(dependency("com.google.code.gson:gson:.*"))
        exclude(dependency("com.google.guava:guava:.*"))
        exclude(dependency("it.unimi.dsi:fastutil:.*"))
        exclude(dependency("org.joml:joml:.*"))
        exclude(dependency("org.xerial:sqlite-jdbc:.*"))
        exclude(dependency("org.mongodb:.*:.*"))
        exclude(project(":reload-api"))
        exclude(project(":reload-host"))
        exclude(project(":reload-services"))
        exclude(project(":reload-integrations"))
    }
    relocate("com.google.protobuf", "ac.cult.cultac.libs.protobuf")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("META-INF/services/javax.annotation.processing.Processor")
    excludeCloudExtras()
    mergeServiceFiles()
    manifest.attributes(mapOf(
        "Cult-Runtime-SPI" to "1",
        "Cult-Runtime-Platform" to "velocity",
        "Cult-Runtime-Java" to "21",
        "Cult-Runtime-Build" to rootProject.version.toString(),
        "Cult-Runtime-Entrypoint" to "ac.cult.cultac.platform.velocity.VelocityRuntimeEntrypoint"
    ))
    doLast {
        verifyCloudRuntimeJar(archiveFile.get().asFile)
    }
}
tasks.assemble { dependsOn(tasks.shadowJar) }
