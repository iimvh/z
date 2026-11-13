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
