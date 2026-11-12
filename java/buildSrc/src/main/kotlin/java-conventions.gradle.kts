// Shared configuration for EVERY Java module in the project.
// A new module (harness, plugin, ...) only needs: plugins { id("java-conventions") }

plugins {
    java
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Every compiler warning is an error: no silent technical debt accumulates.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<JavaExec>().configureEach {
    // Print Unicode text correctly on every console.
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
