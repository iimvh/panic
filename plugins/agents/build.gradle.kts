plugins {
    id("java-conventions")
    `java-library`
}

dependencies {
    api(project(":chat")) // Chat.ASK and Chat.Delegate: a sub-agent is a delegated ask
    api(project(":tools")) // Tools.RUN / Tools.APPROVE: the sub-agent's tool calls
    api(project(":llm")) // Llm.KEY and the message types
}
