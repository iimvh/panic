plugins {
    id("java-conventions")
    `java-library`
}

dependencies {
    api(project(":chat")) // Chat.contribute: instructions and the memory index go into the system prompt
    api(project(":tools")) // the memory tools register through Tools hooks; Workspace.root finds the project
    api(project(":llm")) // Json and the tool-call types
}
