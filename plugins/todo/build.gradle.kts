plugins {
    id("java-conventions")
    `java-library`
}

dependencies {
    api(project(":chat")) // Chat.contribute: the list goes into the system prompt
    api(project(":tools")) // the todo tool registers through Tools hooks
    api(project(":llm")) // Json and the tool-call types
}
