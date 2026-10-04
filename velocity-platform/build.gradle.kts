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
val vanillaFiles = project(":vanilla-runtime").layout.buildDirectory.dir("runtime")
dependencies {
    implementation(project(":common"))
    implementation(project(":protocol-netty"))
    implementation(project(":vanilla-runtime"))
    implementation(libs.cult.internal.shims)
    implementation("org.incendo:cloud-velocity:2.0.0-beta.10")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    compileOnly("com.velocitypowered:velocity-api:3.4.0")
    compileOnly(files(vanillaFiles.map { it.file("client.jar") }))
    compileOnly(fileTree(vanillaFiles.map { it.dir("lib") }) { include("**/*.jar") })
    compileOnly("io.netty:netty-handler:4.2.18.Final")
    testImplementation("com.velocitypowered:velocity-api:3.4.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.mockito:mockito-core:5.22.0")
    testImplementation(files(vanillaFiles.map { it.file("client.jar") }))
    testImplementation(fileTree(vanillaFiles.map { it.dir("lib") }) { include("**/*.jar") })
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(libs.viaversion)
}
tasks.compileJava { dependsOn(":vanilla-runtime:prepareVanilla") }
tasks.test {
    dependsOn(":vanilla-runtime:prepareVanilla")
    useJUnitPlatform()
}
tasks.shadowJar {
    archiveFileName.set("cult-engine.jar")
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("META-INF/services/javax.annotation.processing.Processor")
}
