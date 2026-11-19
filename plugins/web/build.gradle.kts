plugins {
    id("java-conventions")
    `java-library`
}

dependencies {
    api(project(":chat")) // Chat.ASK, Conversations hooks
    api(project(":tools")) // Tools.APPROVE
    api(project(":planner")) // keys only: Planner.APPROVE, Planner.MODE
    api(project(":interact")) // keys only: AskUser.ASK
    api(project(":llm")) // Llm.KEY, providers and message types
}

// The UI (a SvelteKit folder at the repo root) is built with npm and packed into the jar under panic/web/ui.
// -Pui=<folder> picks which one (default ui-v1). Pass -PskipUi to build without Node: the server then
// answers with a "UI not built" page.
val uiDir = rootProject.layout.projectDirectory.dir(providers.gradleProperty("ui").getOrElse("ui-v1"))
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
    description = "Builds the selected SvelteKit UI (-Pui, default ui-v1) into <ui>/build."
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
