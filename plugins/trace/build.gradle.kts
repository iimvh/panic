plugins {
    id("java-conventions")
    `java-library`
}

// Stores the spans of panic.trace (sdk); Jackson writes them as JSON lines. Any plugin emitting Trace.SPAN is
// traced; :chat is used only for the key Conversations.DELETED.
dependencies {
    api(project(":sdk"))
    api(project(":chat"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
}
