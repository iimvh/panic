plugins {
    id("java-conventions")
    `java-library`
}

dependencies {
    // :todo for Todo.PLAN (the acceptance criteria); it brings :chat (Chat.REVIEW), :tools and :llm.
    api(project(":todo"))
}
