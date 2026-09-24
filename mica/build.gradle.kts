plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    alias(libs.plugins.vanniktech.maven.publish)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    testImplementation(kotlin("test"))
}
