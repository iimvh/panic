plugins {
    id("java-conventions")
    `java-library`
}

dependencies {
    api(project(":sdk"))
    api("com.fasterxml.jackson.core:jackson-databind:2.18.2")
}
