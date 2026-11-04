plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.client.websockets)
            api(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            api(libs.koin.core)
            api(libs.lifecycle.viewmodel.kmp)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        getByName("desktopMain") {
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation("net.java.dev.jna:jna-platform:${libs.versions.jna.get()}")
            }
        }
        getByName("desktopTest") {
            resources.srcDir("src/commonTest/resources")
            // Tests use kotlinx-coroutines-test helpers that 1.11 marks experimental.
            languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
        }
    }
}

// Child-JVM restart smoke uses precisely this test runtime, not global Java.
tasks.withType<Test>().configureEach {
    doFirst {
        systemProperty("silage.test.classpath", classpath.asPath)
    }
}
