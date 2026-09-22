// Top-level build file where you can add configuration options common to all sub-projects/modules.
//
// AGP 9 ships built-in Kotlin. Declaring the Kotlin plugins here with `apply false`
// keeps Kotlin Gradle Plugin 2.4.20 on the shared build classpath, so built-in
// Kotlin uses 2.4.20 instead of AGP's default (2.3.21). Miuix is built against
// Kotlin 2.4.x, so this matters.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
