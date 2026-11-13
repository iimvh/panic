plugins {
    id("java-conventions")
    `java-library`
}

dependencies {
    api(project(":chat"))
}

// The UI (../ui, SvelteKit) is built with npm and packed into the jar under panic/web/ui.
// Pass -PskipUi to build without Node: the server then answers with a "UI not built" page.
val uiDir = rootProject.layout.projectDirectory.dir("ui")
val skipUi = providers.gradleProperty("skipUi").isPresent
val npm = if (System.getProperty("os.name").lowercase().contains("win")) "npm.cmd" else "npm"

val installUi = tasks.register<Exec>("installUi") {
    description = "Installs the UI's npm dependencies."
    workingDir = uiDir.asFile
    commandLine(npm, "ci", "--no-audit", "--no-fund")
    inputs.files(uiDir.file("package.json"), uiDir.file("package-lock.json"))
    outputs.dir(uiDir.dir("node_modules"))
    enabled = !skipUi
}

val buildUi = tasks.register<Exec>("buildUi") {
    description = "Builds the SvelteKit UI into ui/build."
    group = BasePlugin.BUILD_GROUP
    dependsOn(installUi)
    workingDir = uiDir.asFile
    commandLine(npm, "run", "build")
    inputs.dir(uiDir.dir("src"))
    inputs.dir(uiDir.dir("static")).optional()
    inputs.files(
        uiDir.file("package.json"), uiDir.file("package-lock.json"), uiDir.file("svelte.config.js"),
        uiDir.file("vite.config.ts"), uiDir.file("tsconfig.json"),
    )
    outputs.dir(uiDir.dir("build"))
    enabled = !skipUi
}

tasks.processResources {
    if (!skipUi) {
        dependsOn(buildUi)
        from(uiDir.dir("build")) { into("panic/web/ui") }
    }
}
