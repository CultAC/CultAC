plugins {
    `java-library`
    cult.`format-conventions`
}

repositories { mavenCentral() }

java.toolchain.languageVersion.set(JavaLanguageVersion.of(25))
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

dependencies {
    compileOnly("io.netty:netty-buffer:4.2.18.Final")
    testImplementation("io.netty:netty-buffer:4.2.18.Final")
    testImplementation(testlibs.junitJupiter)
    testRuntimeOnly(testlibs.junitPlatformLauncher)
}

tasks.test { useJUnitPlatform() }

// Proxy and backend consumers include Java 21 runtimes.
for (variant in listOf("apiElements", "runtimeElements")) {
    configurations.named(variant) {
        attributes.attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
    }
}
