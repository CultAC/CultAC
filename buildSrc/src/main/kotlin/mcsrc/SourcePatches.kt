package mcsrc

import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes the difference between two decompiled source trees as one unified diff per class, laid out like the
 * source tree. Each patch is named after the file in [from], so a reader holding the [from] tree finds the patch
 * for a class at the same relative path; classes that only exist in [to] are named after their [to] path.
 */
object SourcePatches {
    fun write(sourcesRoot: Path, from: String, to: String, outputDir: Path) {
        Files.createDirectories(outputDir)
        val process = ProcessBuilder(
            "git", "-c", "core.quotepath=false", "-c", "core.autocrlf=false",
            "diff", "--no-index", "--no-color", "--no-ext-diff", "--find-renames", "-l100000",
            "--src-prefix=a/", "--dst-prefix=b/", from, to,
        )
            .directory(sourcesRoot.toFile())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        process.outputStream.close()

        val changes = mutableListOf<String>()
        // Classes renamed by case only (Enderman -> EnderMan) would share a patch file on Windows and macOS.
        val taken = HashSet<String>()
        var current: FilePatch? = null
        fun flush() {
            // The trees also hold the task's cache stamp; only sources are patched.
            current?.takeIf { it.isSource }?.let { changes += it.finish(outputDir, taken) }
        }
        process.inputStream.bufferedReader().useLines { lines ->
            for (raw in lines) {
                if (raw.startsWith("diff --git ")) {
                    flush()
                    current = FilePatch()
                }
                val patch = current ?: continue
                patch.accept(raw.relativize(from, to))
            }
        }
        flush()

        // 0 means identical trees, 1 means differences; anything else is a failure.
        val exit = process.waitFor()
        check(exit == 0 || exit == 1) { "git diff --no-index $from $to failed with exit code $exit" }

        Files.newBufferedWriter(outputDir.resolve("CHANGES.txt")).use { out ->
            out.write("# Classes that differ going from $from to $to (${changes.size} files).\n")
            out.write("# M = modified, A = only in $to, D = only in $from, R = renamed ($from path -> $to path).\n")
            for (change in changes.sorted()) out.write("$change\n")
        }
    }

    /**
     * Strips the version directory git puts behind the a/ and b/ prefixes, so patches apply with -p1. The header of an
     * added or deleted file names the one tree it exists in on both sides.
     */
    private fun String.relativize(from: String, to: String): String = when {
        startsWith("diff --git ") -> replace(" a/$from/", " a/").replace(" a/$to/", " a/")
            .replace(" b/$to/", " b/").replace(" b/$from/", " b/")
        startsWith("--- a/") -> "--- a/" + removePrefix("--- a/$from/")
        startsWith("+++ b/") -> "+++ b/" + removePrefix("+++ b/$to/")
        startsWith("rename from ") -> "rename from " + removePrefix("rename from $from/")
        startsWith("rename to ") -> "rename to " + removePrefix("rename to $to/")
        else -> this
    }

    private class FilePatch {
        private val lines = ArrayList<String>()
        private var fromPath: String? = null
        private var toPath: String? = null
        private var renameFrom: String? = null
        private var renameTo: String? = null
        private var kind = 'M'

        val isSource: Boolean get() = fromPath!!.endsWith(".java") || toPath!!.endsWith(".java")

        fun accept(line: String) {
            lines += line
            if (lines.size == 1) {
                // diff --git a/<path> b/<path>; MC paths never contain " b/".
                val body = line.removePrefix("diff --git ")
                val split = body.indexOf(" b/")
                fromPath = body.substring(2, split)
                toPath = body.substring(split + 3)
                return
            }
            when {
                line.startsWith("new file mode") -> kind = 'A'
                line.startsWith("deleted file mode") -> kind = 'D'
                line.startsWith("rename from ") -> { kind = 'R'; renameFrom = line.removePrefix("rename from ") }
                line.startsWith("rename to ") -> renameTo = line.removePrefix("rename to ")
            }
        }

        fun finish(outputDir: Path, taken: MutableSet<String>): String {
            val from = renameFrom ?: fromPath!!
            val to = renameTo ?: toPath!!
            val name = if (kind == 'A') to else from
            val file = generateSequence(1) { it + 1 }
                .map { if (it == 1) "$name.patch" else "$name.$it.patch" }
                .first { taken.add(it.lowercase()) }
            val target = outputDir.resolve(file)
            Files.createDirectories(target.parent)
            Files.newBufferedWriter(target).use { out: BufferedWriter ->
                for (line in lines) {
                    out.write(line)
                    out.write("\n")
                }
            }
            val change = when (kind) {
                'R' -> "R $from -> $to"
                'A' -> "A $to"
                else -> "$kind $from"
            }
            return if (file == "$name.patch") change else "$change (patch: $file)"
        }
    }
}
