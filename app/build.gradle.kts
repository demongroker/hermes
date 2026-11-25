plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
}

android {
  namespace = "app.hermes.updater"
  compileSdk = 36

  defaultConfig {
    applicationId = "app.hermes.updater"
    minSdk = 26
    targetSdk = 36
    versionCode = 18
    versionName = "0.1.17"
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlinOptions {
    jvmTarget = "17"
  }

  buildTypes {
    release {
      // Signed in CI from the same keystore env vars the other fork apps use.
      val storePath = System.getenv("HERMES_KEYSTORE")
      if (storePath != null) {
        signingConfig = signingConfigs.create("release") {
          storeFile = file(storePath)
          storePassword = System.getenv("HERMES_STORE_PASSWORD")
          keyAlias = System.getenv("HERMES_KEY_ALIAS")
          keyPassword = System.getenv("HERMES_KEY_PASSWORD")
        }
      }
      isMinifyEnabled = false
    }
  }
}

dependencies {
  implementation(platform("androidx.compose:compose-bom:2025.09.01"))
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-core")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.activity:activity-compose:1.11.0")
  implementation("androidx.core:core-ktx:1.17.0")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
  implementation("androidx.work:work-runtime-ktx:2.10.5")
  implementation("androidx.security:security-crypto:1.1.0-alpha06")

  testImplementation("junit:junit:4.13.2")
}
