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
