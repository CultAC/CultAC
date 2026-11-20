/**
 *          CultAC Build Configuration
 *
 * Build Flags:
 * -Prelocate=false - Adds 'no_relocate' modifier
 * -Prelease=true   - Removes commit/modifiers for release build
 * CULT_BUILD_NUMBER - Numbered build: <base>-b<N>-<channel>-<commit>
 * CULT_BUILD_CHANNEL - The channel of a numbered build; the git branch when unset
 *
 * Logic in: buildSrc/versioning/BuildConfig.kt & VersionUtil.kt
 */

import versioning.BuildConfig
import versioning.VersionUtil

plugins {
    // The Paper host boundary alone compiles against NMS.
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.23" apply false
}

BuildConfig.init(project)

val baseVersion = "0.1.0"
group = "ac.cult.cultac"
version = VersionUtil.computeVersion(project, baseVersion)
description = "Libre simulation anticheat with Java 26.3 client action simulation."

ext["timestamp"] = System.currentTimeMillis().toString()
ext["git_branch"] = VersionUtil.getGitBranch(project, true)
ext["build_channel"] = VersionUtil.buildChannel(project)
ext["git_commit"] = VersionUtil.getGitCommitHash(project, true)
ext["git_org"] = System.getenv("CULT_GIT_ORG") ?: VersionUtil.getGitUser(project)
ext["git_repo"] = System.getenv("CULT_GIT_REPO") ?: "CultAC"

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

// ---------- Decompiled Minecraft patches ----------
// ./gradlew mcsrc: per-class patches from the latest Minecraft release back to every release down to
// runtimeHostMinVersion, in mc-sources/releases/. ./gradlew mcsrcSnapshot: patches from the latest release to every
// snapshot since, in mc-sources/snapshots/. The latest versions come from Mojang's version manifest; both tasks share
// one cache across worktrees, so a version is only decompiled and a pair of versions only diffed once.
tasks.register<mcsrc.GenerateReleasePatches>("mcsrc") {
    description = "Writes patches of the decompiled Minecraft client from the latest release back to older releases"
    minVersion.set(providers.gradleProperty("runtimeHostMinVersion"))
    outputDir.set(layout.projectDirectory.dir("mc-sources/releases"))
}
tasks.register<mcsrc.GenerateSnapshotPatches>("mcsrcSnapshot") {
    description = "Writes patches of the decompiled Minecraft client from the latest release to every snapshot since"
    outputDir.set(layout.projectDirectory.dir("mc-sources/snapshots"))
}
tasks.withType<mcsrc.MinecraftPatchTask>().configureEach {
    decompilerMaxHeap.set(providers.gradleProperty("mcsrc.decompilerHeap").orElse("8g"))
    cacheDir.set(gradle.gradleUserHomeDir.resolve("caches/cultac-mcsrc"))
}

// ---------- Java Compile Optimization ----------
subprojects {
    repositories.maven("https://repo.papermc.io/repository/maven-public/") {
        content { includeGroup("io.papermc.paper") }
    }
    tasks.withType<JavaCompile>().configureEach {
        options.isFork = true
        options.isIncremental = true
    }
}
