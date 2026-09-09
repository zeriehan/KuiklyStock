plugins {
    //trick: for the same plugin versions in all sub-modules
    id("com.android.application").version("7.4.2").apply(false)
    id("com.android.library").version("7.4.2").apply(false)
    kotlin("android").version("2.1.21").apply(false)
    kotlin("multiplatform").version("2.1.21").apply(false)
    id("com.google.devtools.ksp").version("2.1.21-2.0.1").apply(false)

}

buildscript {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://storage.googleapis.com/r8-releases/raw") }
    }
    dependencies {
        classpath(BuildPlugin.kuikly)
        // ⚠️ 覆盖 AGP 7.4.2 自带的旧 R8：旧 R8 无法 dex Kotlin 2.x stdlib(2.1.21) 的 metadata，
        // 全量 dexing(Generate Signed APK → mergeExtDexDebug)会崩 `com.android.tools.r8.kotlin.H`。
        // 需 R8 ≥ 8.5.x(支持 Kotlin 2)。不加则平时的增量 Run 可能侥幸通过、一打包就挂。
        classpath("com.android.tools:r8:8.5.35")
    }
}