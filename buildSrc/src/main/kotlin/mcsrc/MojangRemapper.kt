package mcsrc

import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch
import net.fabricmc.mappingio.format.proguard.ProGuardFileReader
import net.fabricmc.mappingio.tree.MemoryMappingTree
import net.fabricmc.tinyremapper.IMappingProvider
import net.fabricmc.tinyremapper.NonClassCopyMode
import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Remaps an obfuscated Minecraft jar to Mojang's names using the version's ProGuard mappings. */
object MojangRemapper {
    private const val OFFICIAL = "official"
    private const val NAMED = "named"

    fun remap(input: Path, proguardMappings: Path, output: Path) {
        val tree = MemoryMappingTree()
        // ProGuard files map named -> obfuscated; the jar is obfuscated, so read it the other way round.
        Files.newBufferedReader(proguardMappings).use { reader ->
            ProGuardFileReader.read(reader, NAMED, OFFICIAL, MappingSourceNsSwitch(tree, OFFICIAL))
        }
        val named = tree.getNamespaceId(NAMED)

        val mappings = IMappingProvider { acceptor ->
            for (cls in tree.classes) {
                val owner = cls.srcName
                cls.getName(named)?.let { acceptor.acceptClass(owner, it) }
                for (method in cls.methods) {
                    val name = method.getName(named) ?: continue
                    acceptor.acceptMethod(IMappingProvider.Member(owner, method.srcName, method.srcDesc), name)
                }
                for (field in cls.fields) {
                    val name = field.getName(named) ?: continue
                    acceptor.acceptField(IMappingProvider.Member(owner, field.srcName, field.srcDesc), name)
                }
            }
        }

        val remapper = TinyRemapper.newRemapper()
            .withMappings(mappings)
            .rebuildSourceFilenames(true)
            .build()
        // OutputConsumerPath writes a directory tree unless the name ends in .jar.
        val partial = output.resolveSibling("${output.fileName.toString().removeSuffix(".jar")}.part.jar")
        Files.deleteIfExists(partial)
        try {
            OutputConsumerPath.Builder(partial).build().use { consumer ->
                consumer.addNonClassFiles(input, NonClassCopyMode.SKIP_META_INF, remapper)
                remapper.readInputs(input)
                remapper.apply(consumer)
            }
        } finally {
            remapper.finish()
        }
        Files.move(partial, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
