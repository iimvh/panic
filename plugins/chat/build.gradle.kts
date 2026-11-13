plugins {
    id("java-conventions")
    `java-library`
}

dependencies {
    api(project(":llm"))
    api(project(":tools"))
}
