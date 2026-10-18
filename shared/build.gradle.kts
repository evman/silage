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
        val desktopMain by getting {
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation(libs.java.keyring)
                implementation("net.java.dev.jna:jna-platform:${libs.versions.jna.get()}")
            }
        }
        val desktopTest by getting {
            resources.srcDir("src/commonTest/resources")
        }
    }
}

// Child-JVM restart smoke uses precisely this test runtime, not global Java.
tasks.withType<Test>().configureEach {
    doFirst {
        systemProperty("silage.test.classpath", classpath.asPath)
        rootProject.layout.buildDirectory.file("remember-test-classpath.txt").get().asFile.writeText(classpath.asPath)
    }
}
