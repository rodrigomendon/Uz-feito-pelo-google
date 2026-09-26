import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import org.gradle.api.tasks.bundling.Zip

plugins {
  alias(libs.plugins.android.application)
  // alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.rodrigo.ultrazoom"
    minSdk = 30
    targetSdk = 36
    versionCode = 36
    versionName = "12.4.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = false
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Unused template dependencies are commented out to optimize APK size and build time.
dependencies {
  // implementation(platform(libs.androidx.compose.bom))
  // implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  // implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  // implementation(libs.androidx.compose.material.icons.core)
  // implementation(libs.androidx.compose.material.icons.extended)
  // implementation(libs.androidx.compose.material3)
  // implementation(libs.androidx.compose.ui)
  // implementation(libs.androidx.compose.ui.graphics)
  // implementation(libs.androidx.compose.ui.tooling.preview)
  // implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  // implementation(libs.androidx.lifecycle.runtime.compose)
  // implementation(libs.androidx.lifecycle.runtime.ktx)
  // implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  // implementation(libs.androidx.room.ktx)
  // implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  // implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  // implementation(libs.firebase.firestore)
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  // implementation(libs.firebase.appcheck.recaptcha)
  // implementation(libs.firebase.appcheck.debug)
  // implementation(libs.kotlinx.coroutines.android)
  // implementation(libs.kotlinx.coroutines.core)
  // implementation(libs.logging.interceptor)
  // implementation(libs.moshi.kotlin)
  // implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  // implementation(libs.retrofit)
  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  // "ksp"(libs.androidx.room.compiler)
  // "ksp"(libs.moshi.kotlin.codegen)
}

val packageUltraZoomZip by tasks.registering(Zip::class) {
  archiveFileName.set("UltraZoom-00-v12.4.0-AUTODIAGNOSTIC.zip")
  destinationDirectory.set(rootProject.projectDir)
  from(rootProject.projectDir) {
    include(
      ".github/**",
      "README.md",
      "BUILD_APK.md",
      "VALIDACAO*.txt",
      "app/src/main/AndroidManifest.xml",
      "app/src/main/java/com/rodrigo/**",
      "app/src/main/res/**",
      "app/src/test/java/com/rodrigo/**"
    )
  }
  from(rootProject.file("standalone-gradle")) {
    include("build.gradle", "settings.gradle", "gradle.properties", "app/build.gradle")
  }
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
  finalizedBy(packageUltraZoomZip)
}
