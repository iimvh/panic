plugins {
    id("java-conventions")
    `java-library`
}

dependencies {
    api(project(":todo")) // Todo.PLAN: the acceptance criteria
    api(project(":chat")) // Chat.REVIEW
    api(project(":tools")) // the check tool registers through Tools hooks
    api(project(":llm")) // Llm.KEY for the reviewer's model call
}
