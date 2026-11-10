plugins { `java-library`; cult.`format-conventions` }
java.toolchain.languageVersion.set(JavaLanguageVersion.of(25))
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }

repositories { mavenCentral() }
