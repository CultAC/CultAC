package mcsrc

import org.gradle.api.tasks.TaskAction

/**
 * Writes patches from the latest Minecraft release to every snapshot, pre-release and release candidate published
 * since, all of which Mojang's version manifest lists as snapshots.
 */
abstract class GenerateSnapshotPatches : MinecraftPatchTask() {
    @TaskAction
    fun generate() {
        val meta = meta()
        val manifest = meta.manifest()
        val latest = manifest.versions.first { it.id == manifest.latest.release }
        val snapshots = manifest.versions
            .filter { it.type == "snapshot" && it.released > latest.released }
            .sortedByDescending { it.released }
        publish(
            meta, latest, snapshots,
            "Every snapshot published since ${latest.id}, the latest release. Older releases are in `./gradlew mcsrc`.",
        )
    }
}
