import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localSecrets = Properties().apply {
    val secretsFile = rootProject.file("local-secrets.properties")
    if (secretsFile.exists()) {
        secretsFile.reader(Charsets.UTF_8).use(::load)
    }
}

val localBrand = Properties().apply {
    val brandFile = rootProject.file("local-brand.properties")
    if (brandFile.exists()) {
        brandFile.reader(Charsets.UTF_8).use(::load)
    }
}

fun quotedBuildConfig(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.zaqizaba.rainassistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zaqizaba.rainassistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField(
            "String",
            "DEFAULT_DEEPSEEK_API_KEY",
            quotedBuildConfig(localSecrets.getProperty("DEEPSEEK_API_KEY", "").trim()),
        )
        buildConfigField(
            "String",
            "DEFAULT_DEEPSEEK_API_LABEL",
            quotedBuildConfig(
                localBrand.getProperty("DEEPSEEK_API_LABEL", "内置测试 API Key")
                    .trim()
                    .ifBlank { "内置测试 API Key" },
            ),
        )
        buildConfigField("String", "DEEPSEEK_BASE_URL", quotedBuildConfig("https://api.deepseek.com"))
        buildConfigField("String", "DEEPSEEK_MODEL", quotedBuildConfig("deepseek-v4-flash-vision-exp"))
        buildConfigField("String", "YUKETANG_BASE_URL", quotedBuildConfig("https://www.yuketang.cn"))
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
