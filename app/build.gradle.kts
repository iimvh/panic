plugins {
    id("java-conventions")
    application
}

dependencies {
    implementation(project(":sdk"))
    // Every plugin module is on the app's classpath; the app finds their PluginSets with ServiceLoader.
    rootDir.resolve("plugins").listFiles { f -> f.isDirectory && f.resolve("build.gradle.kts").isFile }
        ?.sortedBy { it.name }
        ?.forEach { implementation(project(":${it.name}")) }
}

application {
    mainClass = "panic.app.Main"
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    workingDir = rootProject.projectDir
}

// ConventionsTest reads the repo itself (docs, READMEs, plugin sources, build files); declare them as inputs so
// Gradle reruns it when they change instead of reporting the test as up to date.
tasks.test {
    inputs.files(rootProject.layout.projectDirectory.file("AGENTS.md"), rootProject.layout.projectDirectory.file("CLAUDE.md"))
        .withPropertyName("agentDocs").optional()
    inputs.files(rootProject.fileTree("plugins") { include("*/README.md", "*/build.gradle.kts", "*/src/main/**") })
        .withPropertyName("pluginFiles")
    inputs.files(rootProject.fileTree("sdk") { include("build.gradle.kts", "src/main/**") }).withPropertyName("sdkFiles")
}
