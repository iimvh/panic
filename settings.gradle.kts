plugins {
    // Lets Gradle download a JDK 21 toolchain when the machine has none (e.g. only a JRE).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "panic"

// z (lifecycle kernel + plugins/services) is consumed from source as a composite build.
includeBuild("z/java") {
    name = "z"
}

// llm  : provider SPI, OpenAI-compatible client, OpenRouter / Mistral plugins, active-model selector.
// chat : conversations store and the chat plugin (history + streaming replies).
// app  : config file, hot reload, terminal REPL bot.
include("llm", "chat", "app")
