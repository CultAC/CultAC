/**
 *          GrimAC Build Configuration
 *
 * Build Flags:
 * -Prelocate=false - Adds 'no_relocate' modifier
 * -Prelease=true   - Removes commit/modifiers for release build
 *
 * Logic in: buildSrc/versioning/BuildConfig.kt & VersionUtil.kt
 */

import versioning.BuildConfig
import versioning.VersionUtil

plugins {
    // Shared classloader for paperweight-userdev across subprojects (common, bukkit,
    // legacy-placement-adapter apply it without a version).
    id("io.papermc.paperweight.userdev") version "2.0.0-SNAPSHOT" apply false
}

BuildConfig.init(project)

val baseVersion = "2.3.74"
group = "ac.grim.grimac"
version = VersionUtil.computeVersion(project, baseVersion)
description = "Libre simulation anticheat designed for 26.2 with 1.21.2+ server and client support."

ext["timestamp"] = System.currentTimeMillis().toString()
ext["git_branch"] = VersionUtil.getGitBranch(project, true)
ext["git_commit"] = VersionUtil.getGitCommitHash(project, true)
ext["git_org"] = System.getenv("GRIM_GIT_ORG") ?: VersionUtil.getGitUser(project)
ext["git_repo"] = System.getenv("GRIM_GIT_REPO") ?: "Grim"

println("Build configuration:")
println("    relocate           = ${BuildConfig.relocate}")
println("    mavenLocalOverride = ${BuildConfig.mavenLocalOverride}")
println("    release            = ${BuildConfig.release}")
println("    version            = $version")

tasks.register("printVersion") {
    group = "versioning"
    description = "Prints the computed project version"
    doLast {
        println("VERSION=$version")
    }
}

// The parity implementation lives in its own tool module so it can load both
// jars in isolated JVMs and package the bootstrap-safe ASM agent without
// changing either runtime jar.  The shell wrapper supplies the baseline and
// current jar paths after building them from clean inputs.
tasks.register("grimParity") {
    group = "verification"
    description = "Runs the exhaustive shared-check differential conformance harness."
    dependsOn(":parity:grimParity")
}

// ---------- Java Compile Optimization ----------
subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.isFork = true
        options.isIncremental = true
    }
}
