// greenWave logic (speed advice, stop detection, rhythm estimation), ported
// from the greenWave app's KMP `shared` module. Plain Kotlin/JVM so it can be
// unit-tested without Android and stays out of CoMaps' own sources.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
