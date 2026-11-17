plugins {
    id("java-conventions")
    `java-library`
}

dependencies {
    api(project(":chat")) // Chat.contribute: a short hint on when to ask
    api(project(":tools")) // ask_user registers through Tools hooks
    api(project(":llm")) // ToolSpec
}

dependencies {
    testImplementation(project(":planner")) // ask_user stays offered in plan mode
}
