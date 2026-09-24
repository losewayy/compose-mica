plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":mica"))
    implementation(project(":glass"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.backdrop.desktop)
    implementation(libs.shapes.desktop)
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
            )
            packageName = "ComposeMicaDemo"
            packageVersion = "0.1.0"
        }
    }
}
