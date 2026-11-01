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
    implementation(project(":reload-host"))
    implementation(libs.cult.internal.shims)
    compileOnly("io.netty:netty-handler:4.2.18.Final")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0")
}
val bootstrapVersion = tasks.register("bootstrapVersion") {
    val output = layout.buildDirectory.file("generated/version/ac/cult/velocity/CultBuildVersion.java")
    val version = rootProject.version.toString()
    val branch = rootProject.ext["build_channel"]?.toString() ?: ""
    val repository = "${rootProject.ext["git_org"]}/${rootProject.ext["git_repo"]}"
    inputs.property("version", version)
    inputs.property("branch", branch)
    inputs.property("repository", repository)
    outputs.file(output)
    doLast {
        fun literal(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                "package ac.cult.velocity; final class CultBuildVersion { static final String VERSION = ${literal(version)};" +
                    " static final String BRANCH = ${literal(branch)}; static final String REPOSITORY = ${literal(repository)}; }\n"
            )
        }
    }
}
sourceSets.main { java.srcDir(layout.buildDirectory.dir("generated/version")) }
tasks.compileJava { dependsOn(bootstrapVersion) }
evaluationDependsOn(":velocity-platform")
val engine = project(":velocity-platform").tasks.named<ShadowJar>("shadowJar")
tasks.shadowJar {
    dependsOn(engine)
    from(engine.flatMap { it.archiveFile }) { into("cult-runtime"); rename { "default.jar" } }
    archiveFileName.set("CultAC-velocity.jar")
    // Velocity ships these (3.4.0+) and its plugin classloader is parent-first, so bundled
    // copies would never be loaded. It does not ship joml or sqlite-jdbc, and 3.x ships only
    // part of fastutil; those stay, fastutil pruned below to the classes something reaches.
    dependencies {
        exclude(dependency("com.google.guava:guava:.*"))
        exclude(dependency("com.google.guava:failureaccess:.*"))
        exclude(dependency("com.google.code.gson:gson:.*"))
        exclude(dependency("org.yaml:snakeyaml:.*"))
        exclude(dependency("org.slf4j:slf4j-api:.*"))
    }

    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("META-INF/services/javax.annotation.processing.Processor")
    doLast { pruneUnreferencedClasses(archiveFile.get().asFile, listOf("it/unimi/dsi/fastutil/")) }

}
tasks.assemble { dependsOn(tasks.shadowJar) }


tasks.compileJava {
    val loaderVersion = providers.gradleProperty("cultLoaderVersion").get()
    inputs.property("cult-loader-version", loaderVersion)
    doLast {
        val descriptor = destinationDirectory.file("velocity-plugin.json").get().asFile
        val json = descriptor.readText().trim()
        val existing = Regex("""["]cult-loader-version["]\s*:\s*["][^"]*["]""")
        val field = "\"cult-loader-version\":\"$loaderVersion\""
        descriptor.writeText(if (existing.containsMatchIn(json)) existing.replace(json, field) else json.dropLast(1) + "," + field + "}\n")
    }
}
