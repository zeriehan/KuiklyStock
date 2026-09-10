import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

/**
 * 读取 AI 大模型 API Key，优先级：
 *   1. local.properties 的 GLM_API_KEY（本机覆盖，该文件不入库）
 *   2. 环境变量 GLM_API_KEY（便于 CI）
 *   3. glm.default.properties 里的共享 Key（随仓库提供，让评审 clone 下来零配置就能用真 AI）
 *   4. 都没有则为空串，App 自动回退本地 Mock，功能演示不受影响。
 * 想换自己的 Key：在 local.properties 里覆盖即可，不用改仓库里的默认文件。
 */
val glmApiKey: String = run {
    fun readKey(file: java.io.File): String? {
        if (!file.exists()) return null
        val props = Properties()
        file.inputStream().use { props.load(it) }
        return props.getProperty("GLM_API_KEY")?.takeIf { it.isNotBlank() }
    }
    readKey(rootProject.file("local.properties"))
        ?: System.getenv("GLM_API_KEY")?.takeIf { it.isNotBlank() }
        ?: readKey(rootProject.file("glm.default.properties"))
        ?: ""
}

android {
    namespace = "com.zeriehan.kuiklystock"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.zeriehan.kuiklystock"
        minSdk = 23
        targetSdk = 30
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "GLM_API_KEY", "\"$glmApiKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(project(":shared"))

    implementation("androidx.recyclerview:recyclerview:1.2.1")
    implementation("androidx.appcompat:appcompat:1.3.1")

    implementation("com.squareup.picasso:picasso:2.71828")

    implementation("androidx.core:core-ktx:1.6.0")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    implementation("com.github.bumptech.glide:glide:4.12.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.12.0")
}