plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
}

android {
  namespace = "com.ichi2.anki.wearos.phone"
  compileSdk = 35
  // Match the NDK that cargo-ndk uses (ndk.dir) so AGP can strip the bundled .so and doesn't warn.
  ndkVersion = "27.0.12077973"

  defaultConfig {
    applicationId = "com.ichi2.anki.wearos"
    minSdk = 26
    targetSdk = 35
    versionCode = 1
    versionName = "1.0.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
  sourceSets { getByName("main") { java.srcDirs("src/main/java", "../bindings") } }

  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      all {
        // Point JNA at the host-built libwearos_core.so so the Rust core is callable from JVM
        // tests.
        it.systemProperty("jna.library.path", rootProject.file("core/target/debug").absolutePath)
        it.dependsOn(":compileRustHost")
      }
    }
  }
}

kotlin { jvmToolchain(21) }

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
  dependsOn(":generateBindings")
}

dependencies {
  implementation(libs.kotlin.stdlib)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("com.google.android.material:material:1.12.0")
  implementation(libs.play.services.wearable)
  implementation("net.java.dev.jna:jna:5.14.0@aar")
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.play.services)

  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
  testImplementation(libs.mockk)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.androidx.test.ext.junit)
  // Plain desktop JNA jar so uniffi's Native.load works under the host test JVM.
  testImplementation(libs.jna.jvm)
}
