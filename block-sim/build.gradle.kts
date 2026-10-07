plugins { `java-library` }

group = rootProject.group
version = rootProject.version

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

repositories { mavenCentral() }

dependencies {
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.apache.commons:commons-lang3:3.20.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register("verifyCompleteManifest") {
    group = "verification"
    description = "Cutover gate: fail if any audited 26.3 method is still unmapped."
    inputs.file(layout.projectDirectory.file("manifest/26.3.tsv"))
    doLast {
        val unmapped = layout.projectDirectory.file("manifest/26.3.tsv").asFile.readLines()
            .drop(1).filter { it.split('\t')[4] == "unmapped" }
        check(unmapped.isEmpty()) { "Incomplete block simulator: ${unmapped.size} source methods remain unmapped." }
    }
}
