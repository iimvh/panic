plugins {
    id("java-conventions")
    `java-library`
}

dependencies {
    api(project(":chat")) // Chat.contribute: the plan mode section of the system prompt
    api(project(":tools")) // Tools.GATE keeps writes out, exit_plan_mode registers through Tools hooks
    api(project(":todo")) // Todo.WRITE: an approved plan becomes the task list
    api(project(":llm")) // ToolSpec, ToolCall
}
