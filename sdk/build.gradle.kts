plugins {
    id("java-conventions")
    `java-library`
}

// What plugin authors use: commands (panic.command) and plugin declaration (panic.plugin).
// No library dependencies, only z.
dependencies {
    api("z:context")
}
