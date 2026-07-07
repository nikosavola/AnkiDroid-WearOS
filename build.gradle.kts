import org.gradle.internal.os.OperatingSystem

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false
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
