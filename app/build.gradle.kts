import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":shared"))
    implementation(project(":player"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.koin.test)
    testImplementation(libs.ktor.client.mock)
}

tasks.test { useJUnitPlatform() }

compose.desktop {
    application {
        mainClass = "dev.silage.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.AppImage)
            packageName = "silage"
            packageVersion = "0.1.0"
        }
    }
}

tasks.register<JavaExec>("runOverlaySpike") {
    group = "spike"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.silage.app.spike.OverlaySpikeKt")
    args = listOfNotNull(project.findProperty("video") as String?)
}

tasks.register<JavaExec>("runEmbedSpike") {
    group = "spike"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.silage.app.spike.EmbedSpikeKt")
    args = listOfNotNull(project.findProperty("video") as String?, project.findProperty("mpvLog") as String?)
}
