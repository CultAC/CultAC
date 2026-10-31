package mcsrc

import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Writes patches from the latest Minecraft release back to every release down to [minVersion], the only pinned
 * version; the latest release comes from Mojang's version manifest.
 */
abstract class GenerateReleasePatches : MinecraftPatchTask() {
    @get:Input
    abstract val minVersion: Property<String>

    @TaskAction
    fun generate() {
        val meta = meta()
        val manifest = meta.manifest()
        val versions = manifest.versions.associateBy { it.id }
        val latest = versions.getValue(manifest.latest.release)
        val min = versions[minVersion.get()]
            ?: throw GradleException("Minecraft version '${minVersion.get()}' is not in Mojang's version manifest")
        if (min.type != "release") throw GradleException("The pinned min version ${min.id} must be a release")
        if (min.released > latest.released) throw GradleException("The pinned min version ${min.id} is newer than ${latest.id}")

        val older = manifest.versions
            .filter { it.type == "release" && it.released >= min.released && it.released < latest.released }
            .sortedByDescending { it.released }
        publish(
            meta, latest, older,
            "Every release from ${latest.id}, the latest, back to ${min.id}, the pinned minimum. Snapshots are in " +
                "`./gradlew mcsrcSnapshot`.",
        )
    }
}
