import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.io.File
import java.util.zip.ZipFile
import org.gradle.api.file.FileTreeElement
import org.gradle.api.specs.Spec

/** The platforms whose zstd-jni native a runtime jar carries, unrelocated (its JNI symbols name its package). */
val zstdNatives = setOf("linux/amd64", "linux/aarch64", "win/amd64", "darwin/x86_64", "darwin/aarch64")

/** Leaves zstd-jni's natives for every platform outside [zstdNatives], and protobuf's bundled schemas, out of the jar. */
fun ShadowJar.excludeCloudExtras() {
    exclude(Spec<FileTreeElement> {
        it.name.startsWith("libzstd-jni-") && it.relativePath.parent?.pathString !in zstdNatives
    })
    exclude("google/protobuf/**")
    includeEmptyDirs = false
}

/** Fails unless [jar] carries zstd-jni with exactly [zstdNatives], and protobuf relocated without its schemas. */
fun verifyCloudRuntimeJar(jar: File) {
    ZipFile(jar).use { archive ->
        check(archive.getEntry("com/github/luben/zstd/ZstdCompressCtx.class") != null) { "zstd-jni missing from the runtime jar" }
        val natives = archive.entries().asSequence().map { it.name }
            .filter { it.substringAfterLast('/').startsWith("libzstd-jni-") }
            .map { it.substringBeforeLast('/') }
            .toList()
        check(natives.sorted() == zstdNatives.sorted()) { "zstd-jni natives must be exactly $zstdNatives: $natives" }
        check(archive.getEntry("ac/cult/cultac/libs/protobuf/MessageLite.class") != null) { "relocated protobuf missing from the runtime jar" }
        check(archive.entries().asSequence().none { it.name.startsWith("com/google/protobuf/") }) { "unrelocated protobuf in the runtime jar" }
        check(archive.entries().asSequence().none { it.name.startsWith("google/protobuf/") }) { "protobuf schemas in the runtime jar" }
    }
}
