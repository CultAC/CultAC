import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins { java; cult.`format-conventions`; id("com.gradleup.shadow") }
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
java.toolchain.languageVersion.set(JavaLanguageVersion.of(21))
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }
dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.4.0")
    implementation(project(":velocity-platform"))
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0")
}
tasks.shadowJar {
    archiveFileName.set("CultAC-velocity.jar")
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("META-INF/services/javax.annotation.processing.Processor")
    doLast {
        check(ProcessBuilder("python3", rootProject.file("scripts/verify-no-bundled-minecraft.py").path,
            archiveFile.get().asFile.path).inheritIO().start().waitFor() == 0)
        val codecAudit = ProcessBuilder("python3", rootProject.file("scripts/verify-vialib-packaging.py").path,
            "--library", rootProject.project(":protocol-codec").layout.buildDirectory.file("libs/vialib.jar").get().asFile.path,
            archiveFile.get().asFile.path).redirectErrorStream(true).start()
        val codecAuditOutput = codecAudit.inputStream.bufferedReader().use { it.readText() }
        check(codecAudit.waitFor() == 0) { codecAuditOutput }
        logger.lifecycle(codecAuditOutput.trim())
    }
}
tasks.assemble { dependsOn(tasks.shadowJar) }
