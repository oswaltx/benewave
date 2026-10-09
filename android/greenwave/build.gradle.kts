// greenWave logic (speed advice, stop detection, rhythm estimation), ported
// from the greenWave app's KMP `shared` module. Kotlin Multiplatform without
// Android dependencies: the app uses the JVM target, an iOS app the
// GreenWaveShared XCFramework (linking needs macOS, see docs/IOS-KONZEPT.md).
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    jvmToolchain(21)
    jvm()

    val xcf = XCFramework("GreenWaveShared")
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "GreenWaveShared"
            xcf.add(this)
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "1g"
}
