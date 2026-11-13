plugins {
    id("java-conventions")
    `java-library`
}

dependencies {
    api(project(":sdk"))
    api(project(":tools")) // keys only: answers Tools.APPROVE
}
