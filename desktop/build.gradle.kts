plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":engine"))
    // OGG decoding for Java Sound (found automatically through the JDK's service loader)
    runtimeOnly("com.googlecode.soundlibs:vorbisspi:1.0.3.3")
    runtimeOnly("com.googlecode.soundlibs:jorbis:0.0.17.4")
    runtimeOnly("com.googlecode.soundlibs:tritonus-share:0.3.7.4")
}

// The desktop build reuses the Android game code unchanged: android.* is provided by the small Java2D layer in
// desktop/src/main/kotlin/android, and the few platform classes below have desktop versions instead.
val main = sourceSets["main"]
main.kotlin.srcDir("../app/src/main/kotlin")
main.kotlin.exclude("**/MainActivity.kt", "**/Haptics.kt", "**/SampleAudio.kt")
main.resources.srcDir("../app/src/main/assets")
main.resources.exclude("**/*.webp") // backdrops ship as .jpg for desktop (see src/main/resources/art)

application {
    mainClass.set("com.gumdropgala.desktop.DesktopMainKt")
    applicationName = "GumdropGala"
}

tasks.jar { archiveFileName.set("gumdrop-gala-desktop.jar") }
