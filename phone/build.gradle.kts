plugins {
  alias(libs.plugins.android.application)
  // kotlin.android removed: AGP 9 built-in Kotlin (version pinned in the root buildscript
  // classpath).
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "com.ichi2.anki.wearos.phone"
  compileSdk = 36
  // Match the NDK that cargo-ndk uses (ndk.dir) so AGP can strip the bundled .so and doesn't warn.
  ndkVersion = "27.0.12077973"

  defaultConfig {
    applicationId = "com.ichi2.anki.wearos"
    minSdk = 26
    targetSdk = 36
    // Play requires distinct versionCodes per form factor within one listing (same applicationId
    // as wear/build.gradle.kts). Scheme: versionCode = release * 10 + formFactor (0 = phone, 1 =
    // wear), so bumping "release" together on both sides keeps them numerically ordered and never
    // collides. Release 2 -> phone 20, wear 21.
    versionCode = 20
    versionName = "1.1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
  buildFeatures { compose = true }
  // ../bindings is generated UniFFI Kotlin. Under AGP 9 built-in Kotlin, extra dirs must be added
  // to
  // the *kotlin* source set (java.srcDirs no longer feeds Kotlin compilation as the KGP plugin
  // did).
  sourceSets { getByName("main") { kotlin.directories.add("../bindings") } }

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

// The ../bindings UniFFI Kotlin must exist before Kotlin compiles. Match by task name rather than
// the KGP KotlinCompile type so it holds under AGP 9 built-in Kotlin (no KGP import on the
// classpath).
tasks
  .matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }
  .configureEach { dependsOn(":generateBindings") }

dependencies {
  implementation(libs.kotlin.stdlib)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.appcompat)
  implementation(libs.material)

  // Jetpack Compose (Material 3) — phase-1 of the phone UI migration; hosts the animated deck tree.
  implementation(libs.androidx.activity.compose)
  implementation(libs.compose.foundation)
  implementation(libs.compose.ui)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.compose.animation)
  implementation(libs.compose.material3)

  implementation(libs.play.services.wearable)
  implementation(libs.androidx.work.runtime)
  implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.play.services)

  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
  testImplementation(libs.mockk)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.androidx.test.ext.junit)
  // Plain desktop JNA jar so uniffi's Native.load works under the host test JVM.
  testImplementation(libs.jna.jvm)

  // Instrumented tests run against a real AnkiDroid install on a device/emulator: they exercise the
  // ContentProvider contract (deck OPTIONS, card scheduler state) that unit tests can only fake.
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.runner)
}
