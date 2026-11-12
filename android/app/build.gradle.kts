plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    // JVM screenshot tests of the Compose UI: ./gradlew :app:recordPaparazziDebug
    id("app.cash.paparazzi")
}

android {
    namespace = "com.prismaclone.app"
    compileSdk = 36 // Android 16

    defaultConfig {
        applicationId = "com.prismaclone.app"
        minSdk = 29 // Android 10: scoped MediaStore saves without storage permissions
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    // Release signing comes from Gradle properties so no key is ever committed:
    //   ./gradlew :app:assembleRelease -Pprisma.keystore=/path/key.jks \
    //     -Pprisma.keystore.password=... -Pprisma.key.alias=... [-Pprisma.key.password=...]
    val keystore = providers.gradleProperty("prisma.keystore").orNull
    if (keystore != null) {
        signingConfigs.create("release") {
            storeFile = file(keystore)
            storePassword = providers.gradleProperty("prisma.keystore.password").get()
            keyAlias = providers.gradleProperty("prisma.key.alias").get()
            keyPassword = providers.gradleProperty("prisma.key.password").orNull ?: storePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        // Robolectric needs merged resources and the manifest to launch MainActivity on the JVM.
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric and Paparazzi each set up their own Android runtime: give every test class a fresh JVM.
            it.forkEvery = 1
            // Robolectric's Android 16 runtime reaches into JDK internals (Java 17+).
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":prismakit"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Robolectric boots the real activity on an Android 16 (API 36) runtime in unit tests.
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
}
