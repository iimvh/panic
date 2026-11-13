plugins {
    id("java-conventions")
    `java-library`
}

dependencies {
    // :chat for Chat.contribute (the list goes into the system prompt); it brings :tools and :llm.
    api(project(":chat"))
}
