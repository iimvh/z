plugins {
    id("java-conventions")
    `java-library`
}

// context depends on core only, and must stay free of library dependencies.
dependencies {
    api(project(":core"))
}

// Same convention as core: tests are plain programs with main(), run through context.AllTests.
val contextTests = tasks.register<JavaExec>("contextTests") {
    description = "Runs the context test suite."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "context.AllTests"
}

tasks.test {
    enabled = false
    dependsOn(contextTests)
}

tasks.check {
    dependsOn(contextTests)
}
