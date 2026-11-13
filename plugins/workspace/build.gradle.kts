plugins {
    id("java-conventions")
    `java-library`
}

dependencies {
    api(project(":chat")) // Chat.contribute: the workspace goes into the system prompt; Conversations.DELETED
    api(project(":tools")) // Workspace keys, the ones other plugins ask
    api(project(":llm")) // Json
}
