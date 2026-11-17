plugins {
    id("java-conventions")
    `java-library`
}

dependencies {
    api(project(":chat")) // Chat.contribute: the servers' instructions go into the system prompt
    api(project(":tools")) // MCP tools register through Tools hooks
    api(project(":llm")) // ToolSpec
}
