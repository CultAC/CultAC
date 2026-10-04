plugins { java; cult.`format-conventions` }
repositories { mavenCentral(); maven("https://repo.papermc.io/repository/maven-public/") }
java.toolchain.languageVersion.set(JavaLanguageVersion.of(25))
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }
dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.4.0")
    implementation(project(":vanilla-bootstrap"))
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("com.google.code.gson:gson:2.13.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test {
    dependsOn(":vanilla-runtime:prepareVanilla")
    systemProperty("vanillaRuntime", project(":vanilla-runtime").layout.buildDirectory.dir("runtime").get().asFile.absolutePath)
    useJUnitPlatform()
}
tasks.processResources {
    dependsOn(":velocity-platform:shadowJar")
    from(project(":velocity-platform").layout.buildDirectory.file("libs/cult-engine.jar")) { into("runtime") }
}
tasks.jar {
    from(configurations.runtimeClasspath.get().filter { it.name.startsWith("asm-") }.map { zipTree(it) })
    archiveFileName.set("CultAC-velocity.jar")
    from(project(":vanilla-bootstrap").extensions.getByType<SourceSetContainer>()["main"].output)
    doLast {
        check(ProcessBuilder("python3", rootProject.file("scripts/verify-no-bundled-minecraft.py").path,
            archiveFile.get().asFile.path).inheritIO().start().waitFor() == 0)
    }
}
