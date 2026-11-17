plugins {
    id("java-conventions")
    `java-library`
}

dependencies {
    api(project(":tools")) // MCP tools register through Tools hooks
    api(project(":llm")) // ToolSpec
}
