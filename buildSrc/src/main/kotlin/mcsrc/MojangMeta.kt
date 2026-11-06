package mcsrc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.readText

@Serializable
data class VersionManifest(val latest: Latest, val versions: List<VersionEntry>) {
    @Serializable
    data class Latest(val release: String, val snapshot: String)
}

@Serializable
data class VersionEntry(val id: String, val type: String, val url: String, val releaseTime: String, val sha1: String) {
    val released: Instant get() = Instant.parse(releaseTime)
}

@Serializable
data class VersionMeta(val id: String, val downloads: Map<String, Download>)

@Serializable
data class Download(val url: String, val sha1: String, val size: Long)

/** Mojang's launcher metadata and downloads, cached under [cacheDir]. */
class MojangMeta(private val cacheDir: Path) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** Fetches the live manifest so new snapshots show up; falls back to the last copy when offline. */
    fun manifest(): VersionManifest {
        val cached = cacheDir.resolve("version_manifest_v2.json")
        try {
            download(MANIFEST_URL, cached, sha1 = null)
        } catch (e: Exception) {
            if (!cached.exists()) throw e
        }
        return json.decodeFromString(cached.readText())
    }

    fun versionMeta(entry: VersionEntry): VersionMeta {
        val file = cacheDir.resolve("versions/${entry.id}/${entry.id}.json")
        ensure(entry.url, file, entry.sha1)
        return json.decodeFromString(file.readText())
    }

    /**
     * Downloads [url] to [target] unless it is already there. Published versions never change and a download is only
     * moved into place once it matches [sha1], so a file that exists is the right one.
     */
    fun ensure(url: String, target: Path, sha1: String) {
        if (target.exists()) return
        download(url, target, sha1)
    }

    private fun download(url: String, target: Path, sha1: String?) {
        Files.createDirectories(target.parent)
        val partial = target.resolveSibling("${target.fileName}.part")
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofFile(partial))
        if (response.statusCode() != 200) {
            Files.deleteIfExists(partial)
            throw IllegalStateException("GET $url returned HTTP ${response.statusCode()}")
        }
        if (sha1 != null) {
            val actual = sha1(partial)
            if (actual != sha1) {
                Files.deleteIfExists(partial)
                throw IllegalStateException("GET $url: expected sha1 $sha1, got $actual")
            }
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun sha1(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-1")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    }
}
