plugins {
    alias(libs.plugins.android.application)
    // 💖 核心修改：丢掉 kapt，拥抱 KSP
    id("com.google.devtools.ksp")
}

android {
    namespace = "cn.xtay.lovejournal"

    // 保持你原有的 API 36 配置
    compileSdk = 36

    buildFeatures {

        buildConfig = true
    }

    defaultConfig {
        applicationId = "cn.xtay.lovejournal"
        minSdk = 26
        targetSdk = 36
        versionCode = 20203
        versionName = "2.2.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            pickFirsts += "lib/arm64-v8a/libAMapSDK_MAP_v10.0.600.so"
            pickFirsts += "lib/armeabi-v7a/libAMapSDK_MAP_v10.0.600.so"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // 网络请求
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")

    // 高德地图
    implementation("com.amap.api:3dmap:10.0.600")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation(files("libs/material-calendarview-2.0.1.aar"))
    implementation("com.jakewharton.threetenabp:threetenabp:1.4.4")



    implementation("androidx.work:work-runtime-ktx:2.9.0")


    val roomVersion = "2.7.0-alpha01"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    // 这里不再使用 kapt，改用 ksp
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")
// 💖 引入 Glide 图片加载库（专门用来丝滑播放 GIF）
    implementation("com.github.bumptech.glide:glide:4.16.0")


}