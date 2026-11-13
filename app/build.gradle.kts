plugins {
    id("java-conventions")
    application
}

dependencies {
    implementation(project(":llm"))
    implementation(project(":chat"))
}

application {
    mainClass = "panic.app.Main"
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    workingDir = rootProject.projectDir
}
