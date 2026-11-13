plugins {
    id("java-conventions")
    `java-library`
}

// What plugin authors use: commands (panic.command), plugin declaration (panic.plugin), traces (panic.trace)
// and small shared utilities (panic.util: Json, CancelToken). Dependencies: z and Jackson only.
dependencies {
    api("z:context")
    api("com.fasterxml.jackson.core:jackson-databind:2.18.2")
}
