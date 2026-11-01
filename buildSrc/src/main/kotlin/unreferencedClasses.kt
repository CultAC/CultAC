import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Rewrites [jar] without the classes under [packages] (slash form, e.g. `it/unimi/dsi/fastutil/`) that no
 * other entry reaches. Every other entry, including the contents of nested jars and non-class resources,
 * is a root; a kept class's own references are followed. Names are matched in both binary and dotted form
 * anywhere in an entry's bytes, so descriptors, signatures and reflective string literals all count, and
 * the result can only over-keep.
 */
fun pruneUnreferencedClasses(jar: File, packages: List<String>) {
    val entries = readEntries(jar.readBytes())
    val names = packages.joinToString("|") { Regex.escape(it.trimEnd('/')) + "|" + Regex.escape(it.trimEnd('/').replace('/', '.')) }
    val reference = Regex("(?:$names)[A-Za-z0-9_/.$]*")
    val candidates = entries.filter { (entry, _) -> entry.name.endsWith(".class") && packages.any(entry.name::startsWith) }
        .associate { (entry, bytes) -> entry.name to bytes }

    val kept = HashSet<String>()
    val pending = ArrayDeque<ByteArray>()
    fun visit(bytes: ByteArray) {
        // Latin-1 maps each byte to one char, so class names in the constant pool survive verbatim.
        for (match in reference.findAll(String(bytes, Charsets.ISO_8859_1))) {
            // A dotted literal may run on into a member name ("pkg.Type.field"); try each prefix.
            var path = match.value.replace('.', '/')
            while ('/' in path) {
                val name = "$path.class"
                if (name in candidates && kept.add(name)) pending.add(candidates.getValue(name))
                path = path.substringBeforeLast('/')
            }
        }
    }
    fun visitRoot(bytes: ByteArray, name: String) {
        if (name.endsWith(".jar")) readEntries(bytes).forEach { (entry, nested) -> visitRoot(nested, entry.name) }
        else visit(bytes)
    }
    entries.filter { (entry, _) -> entry.name !in candidates }.forEach { (entry, bytes) -> visitRoot(bytes, entry.name) }
    while (pending.isNotEmpty()) visit(pending.removeFirst())

    val dropped = candidates.keys - kept
    if (dropped.isEmpty()) return
    val remaining = entries.map { it.first.name }.filter { it !in dropped && !it.endsWith("/") }
    val emptyDirectories = entries.map { it.first.name }
        .filter { dir -> dir.endsWith("/") && packages.any(dir::startsWith) && remaining.none { it.startsWith(dir) } }
        .toSet()
    val output = File(jar.parentFile, jar.name + ".pruned")
    ZipOutputStream(output.outputStream().buffered()).use { zip ->
        for ((entry, bytes) in entries) {
            if (entry.name in dropped || entry.name in emptyDirectories) continue
            zip.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    check(output.renameTo(jar) || (jar.delete() && output.renameTo(jar))) { "Could not replace $jar" }
}

private fun readEntries(bytes: ByteArray): List<Pair<ZipEntry, ByteArray>> =
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        generateSequence { zip.nextEntry }.map { it to zip.readBytes() }.toList()
    }
