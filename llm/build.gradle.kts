plugins {
    id("java-conventions")
    `java-library`
}

dependencies {
    api("z:context")
    api("com.fasterxml.jackson.core:jackson-databind:2.18.2")
}
