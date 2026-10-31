package mcsrc

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.PathWalkOption
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText

/** Runs Vineflower over [jar] into [output] with [options]. */
fun interface Decompiler {
    fun decompile(jar: Path, output: Path, options: List<String>)
}

/**
 * The cache shared by every worktree's mcsrc tasks. A Minecraft version never changes once published, so a decompiled
 * tree is built once per version and decompiler setup, and a patch set once per pair of versions; when Mojang ships a
 * new version, only that version is decompiled and only the pairs it is part of are diffed.
 */
@OptIn(ExperimentalPathApi::class)
class SourceCache(
    private val root: Path,
    private val meta: MojangMeta,
    private val decompiler: Decompiler,
    private val logger: Logger,
) {
    /** Returns the per-class patches from [from] to [to], diffing them unless the cache already holds this pair. */
    fun patches(from: VersionEntry, to: VersionEntry): Path {
        val names = if (obfuscated(from) || obfuscated(to)) LocalNames.GENERATED else LocalNames.MOJANG
        // The pair alone decides the names, so patch sets need no further key.
        val target = root.resolve("patches/${from.id}_to_${to.id}")
        val stamp = "$PATCH_FORMAT\n${treeStamp(from, names)}\n${treeStamp(to, names)}"
        if (stampOf(target) == stamp) return target

        tree(from, names)
        tree(to, names)
        logger.lifecycle("mcsrc: writing patches ${from.id} -> ${to.id}")
        val partial = partialOf(target)
        SourcePatches.write(root.resolve("sources/${names.dir}"), from.id, to.id, partial)
        partial.resolve(STAMP).writeText(stamp)
        install(partial, target)
        return target
    }

    /** Releases before 26.1 are obfuscated and ship Mojang's mappings; later ones are not obfuscated. */
    private fun obfuscated(entry: VersionEntry) = "client_mappings" in meta.versionMeta(entry).downloads

    private fun treeStamp(entry: VersionEntry, names: LocalNames) =
        "format=2;${VINEFLOWER_JAR.name};${(VINEFLOWER_OPTIONS + names.options).joinToString(" ")}\n${entry.sha1}"

    /** Returns the decompiled tree of [entry], decompiling it unless the cache already holds it. */
    private fun tree(entry: VersionEntry, names: LocalNames): Path {
        val target = root.resolve("sources/${names.dir}/${entry.id}")
        val stamp = treeStamp(entry, names)
        if (stampOf(target) == stamp) return target

        val versionMeta = meta.versionMeta(entry)
        val jarDir = root.resolve("mojang/versions/${entry.id}")
        val clientDownload = versionMeta.downloads["client"]
            ?: throw GradleException("Minecraft ${entry.id} has no client download")
        val client = jarDir.resolve("client.jar")
        logger.lifecycle("mcsrc: downloading ${entry.id}")
        meta.ensure(clientDownload.url, client, clientDownload.sha1)

        val jar = versionMeta.downloads["client_mappings"]?.let { mappingsDownload ->
            val mappings = jarDir.resolve("client.txt")
            meta.ensure(mappingsDownload.url, mappings, mappingsDownload.sha1)
            val named = jarDir.resolve("client-named.jar")
            if (!named.exists()) {
                logger.lifecycle("mcsrc: remapping ${entry.id}")
                MojangRemapper.remap(client, mappings, named)
            }
            named
        } ?: client

        logger.lifecycle("mcsrc: decompiling ${entry.id} (${names.dir} local names)")
        val partial = partialOf(target)
        partial.createDirectories()
        decompiler.decompile(jar, partial, VINEFLOWER_OPTIONS + names.options)
        // --skip-extra-files still leaves a few resources behind; only the sources are wanted.
        partial.walk().filter { it.isRegularFile() && !it.name.endsWith(".java") }.forEach(Files::delete)
        // Children come before their parents in reverse walk order, so a parent emptied by them goes too.
        for (dir in partial.walk(PathWalkOption.INCLUDE_DIRECTORIES).toList().asReversed()) {
            if (dir.isDirectory() && dir.listDirectoryEntries().isEmpty()) Files.delete(dir)
        }
        // Vineflower keeps going when a method runs out of heap and leaves a stub in its place; never cache that.
        val starved = partial.walk().filter { it.isRegularFile() && OUT_OF_HEAP in it.readText() }.count()
        if (starved > 0) {
            partial.deleteRecursively()
            throw GradleException(
                "Vineflower ran out of heap decompiling ${entry.id} ($starved classes); rerun with a larger -Pmcsrc.decompilerHeap"
            )
        }
        partial.resolve(STAMP).writeText(stamp)
        install(partial, target)
        return target
    }

    /** A private build directory beside [target], so worktrees filling the cache at once never share one. */
    private fun partialOf(target: Path): Path {
        target.parent.createDirectories()
        return target.resolveSibling("${target.name}.${UUID.randomUUID()}.part")
    }

    private fun install(partial: Path, target: Path) {
        // Only an entry from an older decompiler setup or patch format, or one another worktree just finished, is here.
        target.deleteRecursively()
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE)
    }

    /** How a decompiled tree names local variables and parameters; trees are only diffed against the same kind. */
    private enum class LocalNames(val dir: String, val options: List<String>) {
        /** Mojang's own names, which unobfuscated releases (26.1 and later) ship. */
        MOJANG("mojang", emptyList()),

        /**
         * Names generated from each variable's type, the same way in every version. Obfuscated releases only carry
         * `$$0`-style local names and no final parameter flags, so a patch against one would otherwise rename every
         * local in every changed class.
         */
        GENERATED(
            "generated",
            listOf("--use-lvt-names=0", "--use-method-parameters=0", "--variable-renaming=jad", "--rename-parameters=1"),
        ),
    }

    companion object {
        const val STAMP = ".mcsrc-stamp"
        private const val OUT_OF_HEAP = "// java.lang.OutOfMemoryError: Java heap space"

        /** Changes whenever the patch layout would, so patch sets are rewritten from the cached trees. */
        private const val PATCH_FORMAT = "patches=3"

        val VINEFLOWER_JAR: Path = Paths.get(ConsoleDecompiler::class.java.protectionDomain.codeSource.location.toURI())

        private val VINEFLOWER_OPTIONS = listOf(
            "--folder",
            "--skip-extra-files",
            "--log-level=warn",
            "--indent-string=    ",
        )

        fun stampOf(dir: Path): String? = dir.resolve(STAMP).takeIf { it.exists() }?.readText()
    }
}
