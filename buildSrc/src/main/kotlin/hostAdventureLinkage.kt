import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import linkage.PaperLinkageCheck
import org.gradle.api.Project
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register

/** The Adventure modules the hosts provide; the runtime jars leave net/kyori out and link against the host's. */
private val hostAdventureModules = listOf(
    "api", "key", "nbt", "text-minimessage", "text-serializer-gson", "text-serializer-json",
    "text-serializer-legacy", "text-serializer-plain"
)

/**
 * Registers checkHostAdventureLinkage as part of check: it fails on Adventure references in the shadow jar that do not
 * link against the oldest Adventure a supported host ships (the hostAdventureMinVersion Gradle property).
 */
fun Project.checkHostAdventureLinkage() {
    val version = providers.gradleProperty("hostAdventureMinVersion").get()
    val adventure = configurations.detachedConfiguration(
        *hostAdventureModules.map { dependencies.create("net.kyori:adventure-$it:$version") }.toTypedArray()
    )
    adventure.attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
    val linkage = tasks.register<PaperLinkageCheck>("checkHostAdventureLinkage") {
        group = "verification"
        description = "Fails on Adventure references in the runtime jar that do not link against Adventure $version."
        classes.from(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
        baseline.from(adventure)
        packages.set(setOf("net/kyori/adventure/"))
        excluded.set(emptySet())
    }
    tasks.named("check") { dependsOn(linkage) }
}
