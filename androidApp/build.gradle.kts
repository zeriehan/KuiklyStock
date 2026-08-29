import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

/**
 * 读取 AI 大模型 API Key，优先级：local.properties > 环境变量 > 空串。
 * local.properties 已在 .gitignore 中，密钥不会进入版本库；
 * 取不到时为空串，App 会自动回退本地 Mock 分析，不影响功能演示。
 */
val glmApiKey: String = run {
    val props = Properties()
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { props.load(it) }
    }
    props.getProperty("GLM_API_KEY")
        ?: System.getenv("GLM_API_KEY")
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