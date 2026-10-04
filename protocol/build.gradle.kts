plugins {
    cult.`base-conventions`
}

repositories { mavenCentral() }

// Keep the shared toolchain/style conventions without its Lombok classpath.
configurations.configureEach {
    setExtendsFrom(extendsFrom.filterNot { it.name == "lombok" })
}

dependencies {
    // Paper 26.3; only the ByteBuf API is used, also provided by older Papers.
    compileOnly("io.netty:netty-buffer:4.2.16.Final")
    testImplementation("io.netty:netty-buffer:4.2.16.Final")
    testRuntimeOnly("io.netty:netty-transport:4.2.16.Final")
    testRuntimeOnly("io.netty:netty-codec-base:4.2.16.Final")
    testRuntimeOnly("com.google.guava:guava:33.5.0-jre")
    testImplementation(testlibs.junitJupiter)
    testRuntimeOnly(testlibs.junitPlatformLauncher)
}

tasks.test { useJUnitPlatform() }

tasks.processTestResources {
    dependsOn(":protocol-codec:shadowJar")
    from(project(":protocol-codec").layout.buildDirectory.file("libs/protocol-codecs.jar")) { into("runtime") }
}

sourceSets.test { java.srcDir("src/fixtures/java") }
