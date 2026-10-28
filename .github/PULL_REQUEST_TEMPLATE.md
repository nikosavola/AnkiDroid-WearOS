## What changed

<!-- One or two sentences, and a link to the issue if there is one. -->

## Checklist

- [ ] `uv run prek run --all-files` passes.
- [ ] `cargo test --manifest-path core/Cargo.toml` passes if the Rust core changed.
- [ ] `./gradlew :phone:testDebugUnitTest :wear:testDebugUnitTest` passes if Kotlin changed.
- [ ] New behavior has a test, or this PR explains why it does not.
