plugins {
    id("java-conventions")
    `java-library`
}

// core has no library dependencies, and it must stay that way.

// The current tests are programs with main() (no JUnit), run through core.AllTests:
// component tests first; the composition tests and the stress test run only if those pass.
val coreTests = tasks.register<JavaExec>("coreTests") {
    description = "Runs the full core test suite (component tests first, composition tests after)."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "core.AllTests"
}

tasks.test {
    // JUnit is not used yet: `gradlew test` delegates to coreTests.
    enabled = false
    dependsOn(coreTests)
}

tasks.check {
    dependsOn(coreTests)
}
