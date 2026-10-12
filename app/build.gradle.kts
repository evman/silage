import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":shared"))
    implementation(project(":player"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.ktor3)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.coroutines.swing)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.koin.test)
    testImplementation(libs.ktor.client.mock)
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
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
    (project.findProperty("show") as String?)?.let { environment("SPIKE_SHOW", it) }
    (project.findProperty("hdr") as String?)?.let { environment("SILAGE_HDR", it) }
    (project.findProperty("mpvOpts") as String?)?.let { environment("SPIKE_MPV_OPTS", it) }
    (project.findProperty("noHud") as String?)?.let { environment("SPIKE_NOHUD", it) }
}

afterEvaluate {
    tasks.named<JavaExec>("run") {
        (project.findProperty("devStart") as String?)?.let { environment("SILAGE_DEV_START", it) }
        (project.findProperty("devBackAfter") as String?)?.let { environment("SILAGE_DEV_BACK_AFTER", it) }
        (project.findProperty("devMute") as String?)?.let { environment("SILAGE_DEV_MUTE", it) }
        (project.findProperty("devOpenVersions") as String?)?.let { environment("SILAGE_DEV_OPEN_VERSIONS", it) }
    }
}

tasks.register<JavaExec>("runLogoRender") {
    group = "spike"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.silage.app.spike.LogoRenderKt")
    args = listOfNotNull(project.findProperty("out") as String?)
}

tasks.register<JavaExec>("runSplashRender") {
    group = "spike"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.silage.app.spike.SplashRenderKt")
    args = listOfNotNull(project.findProperty("out") as String? ?: "build/splash", project.findProperty("fps") as String? ?: "30", project.findProperty("width") as String? ?: "960")
}
