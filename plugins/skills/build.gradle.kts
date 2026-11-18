plugins {
    id("java-conventions")
    `java-library`
}

dependencies {
    api(project(":chat")) // Chat.contribute: the list of skills goes into the system prompt
    api(project(":tools")) // the skill tool registers through Tools hooks; Workspace.root finds the project
    api(project(":llm")) // Json and the tool-call types
}
