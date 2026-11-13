plugins {
    id("java-conventions")
    `java-library`
}

// Stores the spans of panic.trace (sdk); Jackson writes them as JSON lines. No dependency on chat: any plugin
// emitting Trace.SPAN is traced.
dependencies {
    api(project(":sdk"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
}
