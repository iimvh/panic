plugins {
    // Lets Gradle download a JDK 21 toolchain when the machine has none (e.g. only a JRE).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "panic"

// z (lifecycle kernel + plugins/services) is consumed from source as a composite build.
includeBuild("z/java") {
    name = "z"
}

// sdk : what plugin authors use: commands (panic.command) and plugin declaration (panic.plugin).
// app : finds every plugin on the classpath, reads panic.json, hot reload, main.
include("sdk", "app")

// plugins/<name> : one module per plugin family, picked up automatically (project ":<name>").
// A new plugin family = a new folder with a build.gradle.kts; nothing to register here.
file("plugins").listFiles { f -> f.isDirectory && f.resolve("build.gradle.kts").isFile }
    ?.sortedBy { it.name }
    ?.forEach { dir ->
        include(dir.name)
        project(":${dir.name}").projectDir = dir
    }
