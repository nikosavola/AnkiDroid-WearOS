# Releasing

Two things can ship from this repository: the `ankidroid-provider` library and the two app APKs.

## Provider library

`.github/workflows/publish.yml` publishes `fi.nikosavola:ankidroid-provider` to this repository's
GitHub Packages registry on any `v*` tag. It runs the library's unit tests before publishing, and
takes the artifact version from the tag without the leading `v`.

1. Tag the commit and push the tag:

   ```bash
   git tag vX.Y.Z
   git push origin vX.Y.Z
   ```

2. `publish.yml` publishes `fi.nikosavola:ankidroid-provider:X.Y.Z`.

3. GitHub Packages never allows an existing version to be overwritten. If a publish fails halfway,
   delete that version from the package page, then either re-push a new tag or run the workflow
   manually with the `version` input set to the same tag.

4. The same tag builds on [JitPack](https://jitpack.io) on demand, without a token, now that the
   repository is public. The README's dependency snippet shows the coordinates.

No `v*` tag has been pushed yet, so the version in the README is still the next one to cut.

## App APKs

There is no release workflow for the apps yet. CI builds unsigned release APKs to exercise R8, and
those cannot be installed. To publish installable APKs:

1. Generate a release keystore and keep it out of the repository. `.gitignore` already excludes
   `*.jks`, `*.keystore` and `keystore.properties`.
2. Add a `signingConfigs.release` block to both app modules. Use the same key for both APKs.
3. Add a release workflow on `v*` tags that decodes the keystore from repository secrets, builds
   `:phone:assembleRelease` and `:wear:assembleRelease`, verifies both APKs carry the same
   certificate, and attaches them plus the R8 `mapping.txt` files to a GitHub Release.
4. Register `fi.nikosavola.ankidroid.wearos` and the release key's SHA-256 fingerprint in Play
   Console's Android developer verification. This is required for installation on certified
   devices even when the APK is sideloaded, and it does not require a Play Store listing.

## Version numbers

- Application id: `fi.nikosavola.ankidroid.wearos`, shared by both apps.
- `versionCode` is `release * 10 + form factor`, where the phone is 0 and the watch is 1. Bump the
  release number in both build files at the same time.
- `versionName` is an independent literal in each app module. Bump both by hand.
- A release workflow should fail the tag push if either `versionName` does not match the tag.
