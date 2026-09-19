# Releasing

How a version of PatchPilot gets from `main` to GitHub Releases, F-Droid and Google Play. Store
metadata lives in `fastlane/metadata/android/en-US/` and is read by F-Droid directly from the
repo; Play is fed the same files.

## 1. Prepare the release commit

On a release branch off `main`:

1. In `android/app/build.gradle.kts`, bump `versionName` and increase `versionCode` by one.
   `versionCode` must never go down or repeat - Play rejects the upload and F-Droid pins each
   build to it.
2. In `CHANGELOG.md`, rename the "Next release" heading to the version and start a fresh
   "Next release" section above it.
3. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (500 characters at most -
   Play's limit; a condensed version of the changelog section).
4. Refresh screenshots in `fastlane/metadata/android/en-US/images/` if the UI changed. Play
   accepts PNG/JPEG without alpha, each side 320-3840 px, longest side no more than twice the
   shortest.

## 2. Build and check

```
cd android
./gradlew testDebugUnitTest assembleRelease bundleRelease
```

Outputs: `app/build/outputs/apk/release/patchpilot-v<version>.apk` (signed with the release key
from `local.properties`) and `app/build/outputs/bundle/release/app-release.aab`.

Install the release APK - not a debug build - on a phone, connect a real instrument, and run
the regression test from the debug menu (five taps on the instrument name). R8 is only exercised
by release builds.

## 3. Tag and publish on GitHub

```
git tag v<version>
git push origin main v<version>
gh release create v<version> android/app/build/outputs/apk/release/patchpilot-v<version>.apk \
    --title v<version> --notes-file <(sed -n '/^## <version>/,/^## /p' CHANGELOG.md | sed '1d;$d')
```

## 4. F-Droid

*not yet supported*

Nothing to do. `UpdateCheckMode: Tags` in the fdroiddata metadata picks up the new `v*` tag,
opens a merge request with the new `versionCode`, and the build server builds it from the tag.
If the build fails, the F-Droid bot reports it in the fdroiddata issue tracker.

## 5. Google Play

*not yet supported*

Upload `app-release.aab` to the production track in the Play Console (or to a testing track
first), paste the changelog entry as the release notes, and roll out. This can later be
automated with `fastlane supply` against the same metadata directory.

## Signing

The local-only release key in `android/keystore/patchpilot-release.jks` signs GitHub APKs
and is the Play upload key. Losing it means GitHub users can no longer update in place,
so keep a backup of the keystore and `local.properties` outside this machine.

CI (`.github/workflows/android.yml`) signs `main` builds with the same key, read from four repository
secrets that mirror the `local.properties` keys: `RELEASE_KEYSTORE_BASE64` (the `.jks` file,
base64-encoded), `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD`. To set
them from a machine that has the keystore and `local.properties`:

```
cd android
gh secret set RELEASE_KEYSTORE_BASE64 --body "$(base64 -i keystore/patchpilot-release.jks)"
for k in RELEASE_STORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD; do
  sed -n "s/^$k=//p" local.properties | gh secret set "$k"
done
```

A push to `main` without these secrets fails at the signing step rather than producing an unsigned
APK. Pull-request builds never use the key.
