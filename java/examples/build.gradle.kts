plugins {
    id("java-conventions")
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":context"))
}

application {
    mainClass = "Main"
}

// ./gradlew :examples:runPlugins : the context example (plugins, services, events, config, tasks).
tasks.register<JavaExec>("runPlugins") {
    description = "Runs the context example (PluginsMain)."
    group = ApplicationPlugin.APPLICATION_GROUP
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "PluginsMain"
}
