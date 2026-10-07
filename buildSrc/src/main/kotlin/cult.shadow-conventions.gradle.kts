import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import versioning.BuildConfig

plugins {
    id("com.gradleup.shadow")
}

tasks.named<ShadowJar>("shadowJar") {
    minimize {
        // adventure's DataComponentValueConverter gson provider is only referenced via
        // ServiceLoader, so minimize() strips it and adventure's static init then throws
        // (ServiceConfigurationError) on enable. Keep the gson serializer's classes.
        exclude(dependency("net.kyori:adventure-text-serializer-gson:.*"))
    }
    archiveFileName = "${rootProject.name}-${project.name}-${rootProject.version}.jar"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    if (BuildConfig.relocate) {
        relocate("club.minnced", "ac.cult.cultac.shaded.discord-webhooks")
        relocate("org.slf4j", "ac.cult.cultac.shaded.slf4j") // Required by discord-webhooks
        relocate("github.scarsz.configuralize", "ac.cult.cultac.shaded.configuralize")
        relocate("com.github.puregero", "ac.cult.cultac.shaded.com.github.puregero")
        relocate("com.google.code.gson", "ac.cult.cultac.shaded.gson")
        relocate("alexh", "ac.cult.cultac.shaded.maps")
        relocate("okhttp3", "ac.cult.cultac.shaded.okhttp3")
        relocate("okio", "ac.cult.cultac.shaded.okio")
        relocate("org.yaml.snakeyaml", "ac.cult.cultac.shaded.snakeyaml")
        relocate("org.json", "ac.cult.cultac.shaded.json")
        relocate("org.intellij", "ac.cult.cultac.shaded.intellij")
        relocate("org.jetbrains", "ac.cult.cultac.shaded.jetbrains")
        relocate("org.incendo", "ac.cult.cultac.shaded.incendo")
        relocate("io.leangen.geantyref", "ac.cult.cultac.shaded.geantyref") // Required by cloud
        relocate("com.zaxxer", "ac.cult.cultac.shaded.zaxxer") // Database history
    }
    mergeServiceFiles()
}

tasks.named("assemble") {
    dependsOn(tasks.named("shadowJar"))
}
