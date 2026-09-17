import org.gradle.internal.os.OperatingSystem

// AGP 9 built-in Kotlin would otherwise pull its bundled Kotlin (2.3.10 for AGP 9.2). Force our
// Kotlin (2.4.10) onto the buildscript classpath so built-in Kotlin compiles with it and the
// compose-compiler plugin (which must match the Kotlin version exactly) stays aligned.
buildscript {
    // Literal (not libs.versions.kotlin): the version-catalog accessor isn't available this early in
    // buildscript{} evaluation. Keep in sync with `kotlin` in gradle/libs.versions.toml.
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    // kotlin.android removed: AGP 9 provides built-in Kotlin (applying it errors on a duplicate
    // `kotlin` extension). Version is pinned via the buildscript classpath above.
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ktfmt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

subprojects {
    apply(plugin = "com.ncorti.ktfmt.gradle")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    // detekt 2.0 (dev.detekt) is the first line compatible with Gradle 9; it's a pre-release.
    apply(plugin = "dev.detekt")

    configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        // Enable detekt's off-by-default rules too (the Kotlin analog of clippy pedantic/nursery);
        // config/detekt/detekt.yml re-disables only the ones that conflict with our conventions.
        allRules = true
        parallel = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    }

    tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
        exclude("**/bindings/**")
        exclude("**/jniLibs/**")
        exclude("**/uniffi/**")
        exclude("**/wearos_core.kt")
    }

    configure<com.ncorti.ktfmt.gradle.KtfmtExtension> {
        googleStyle()
    }

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        filter {
            exclude("**/bindings/**")
            exclude("**/jniLibs/**")
            exclude("**/uniffi/**")
            exclude("**/wearos_core.kt")
        }
    }

    tasks.withType<com.ncorti.ktfmt.gradle.tasks.KtfmtFormatTask>().configureEach {
        exclude("**/bindings/**")
        exclude("**/jniLibs/**")
        exclude("**/uniffi/**")
        exclude("**/wearos_core.kt")
    }

    tasks.withType<com.ncorti.ktfmt.gradle.tasks.KtfmtCheckTask>().configureEach {
        exclude("**/bindings/**")
        exclude("**/jniLibs/**")
        exclude("**/uniffi/**")
        exclude("**/wearos_core.kt")
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
    delete(file("bindings"))
}

// Helper to determine compiled library file name on the host system
val libExtension = when {
    OperatingSystem.current().isMacOsX -> "dylib"
    OperatingSystem.current().isWindows -> "dll"
    else -> "so"
}
val libPrefix = if (OperatingSystem.current().isWindows) "" else "lib"
val hostLibName = "${libPrefix}wearos_core.${libExtension}"

tasks.register<Exec>("compileRustHost") {
    group = "rust"
    description = "Compile Rust core library for host system"
    workingDir = file("core")
    environment("RUSTFLAGS", "") // Clear target-cpu=native from host
    commandLine("cargo", "build")
}

tasks.register<Exec>("compileRustAndroidWear") {
    group = "rust"
    description = "Compile Rust core library for Wear targets using cargo-ndk"
    workingDir = file("core")
    environment("RUSTFLAGS", "") // Isolate Android builds from host target-cpu flags
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "armeabi-v7a",
        "-t", "x86_64",
        "-o", "${projectDir}/wear/src/main/jniLibs",
        "build", "--release"
    )
}

tasks.register<Exec>("compileRustAndroidPhone") {
    group = "rust"
    description = "Compile Rust core library for Phone targets using cargo-ndk"
    workingDir = file("core")
    environment("RUSTFLAGS", "") // Isolate Android builds from host target-cpu flags
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "armeabi-v7a",
        "-t", "x86_64",
        "-o", "${projectDir}/phone/src/main/jniLibs",
        "build", "--release"
    )
}

tasks.register<Exec>("generateBindings") {
    group = "rust"
    description = "Generate Kotlin bindings using uniffi-bindgen"
    dependsOn("compileRustHost", "compileRustAndroidWear", "compileRustAndroidPhone")
    workingDir = file("core")
    environment("RUSTFLAGS", "")
    commandLine(
        "cargo", "run", "--bin", "uniffi-bindgen", "generate",
        "--library", "target/debug/$hostLibName",
        "--language", "kotlin",
        "--out-dir", "../bindings"
    )
}

tasks.register<Exec>("rustFmt") {
    group = "formatting"
    description = "Auto-format Rust core code using cargo fmt"
    workingDir = file("core")
    commandLine("cargo", "fmt", "--all")
}

tasks.register<Exec>("rustLint") {
    group = "verification"
    description = "Lint Rust core code using cargo clippy"
    workingDir = file("core")
    commandLine("cargo", "clippy", "--all-targets", "--", "-D", "warnings")
}

tasks.register("formatAll") {
    group = "formatting"
    description = "Auto-format both Kotlin and Rust codebases"
    dependsOn(":phone:ktfmtFormat", ":wear:ktfmtFormat", "rustFmt")
}

tasks.register("lintAll") {
    group = "verification"
    description = "Run linter checks on both Kotlin and Rust codebases"
    dependsOn(
        ":phone:ktfmtCheck",
        ":wear:ktfmtCheck",
        ":phone:ktlintCheck",
        ":wear:ktlintCheck",
        ":phone:detekt",
        ":wear:detekt",
        "rustLint"
    )
}
