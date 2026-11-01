import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

/** Shaded BouncyCastle; jnats reaches only its Ed25519 signer, so a runtime jar prunes the rest. */
const val SHADED_BOUNCYCASTLE = "ac/cult/cultac/shaded/bouncycastle/"

/**
 * Relocates the Redis and NATS clients the network transports bundle, with their dependencies. BouncyCastle's
 * multi-release copies and JCA provider registration go, since the runtime jar is not multi-release and both would
 * keep every BouncyCastle class past the prune, and so do its native accelerators, which Ed25519 never loads.
 */
fun ShadowJar.shadeNetworkClients() {
    relocate("redis.clients", "ac.cult.cultac.shaded.redis")
    relocate("org.apache.commons.pool2", "ac.cult.cultac.shaded.pool2")
    relocate("org.json", "ac.cult.cultac.shaded.json") { exclude("org.json.simple.**") }
    relocate("io.nats", "ac.cult.cultac.shaded.nats")
    relocate("org.bouncycastle", "ac.cult.cultac.shaded.bouncycastle")
    exclude(
        "META-INF/versions/*/org/bouncycastle/**",
        "META-INF/versions/*/$SHADED_BOUNCYCASTLE**",
        "META-INF/services/java.security.Provider",
        "native/**",
    )
}
