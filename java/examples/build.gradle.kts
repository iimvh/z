plugins {
    id("java-conventions")
    application
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass = "Main"
}
