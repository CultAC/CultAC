/**
 *          CultAC Build Configuration
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
