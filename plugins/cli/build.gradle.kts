plugins {
    id("java-conventions")
    `java-library`
}

dependencies {
    api(project(":sdk"))
    api(project(":tools")) // keys only: answers Tools.APPROVE
    api(project(":planner")) // keys only: answers Planner.APPROVE, shows Planner.MODE
    api(project(":interact")) // keys only: answers AskUser.ASK
}
