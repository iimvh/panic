plugins {
    id("java-conventions")
    `java-library`
}

dependencies {
    // :chat for Chat.ASK and Chat.Delegate (a sub-agent is a delegated ask); it brings :tools and :llm.
    api(project(":chat"))
}
