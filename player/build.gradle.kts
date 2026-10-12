plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(21) }

dependencies {
    api(libs.jna)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test { useJUnitPlatform() }
