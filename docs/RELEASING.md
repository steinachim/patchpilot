# Releasing

How a version of Patch Pilot gets from `dev` to GitHub Releases, and, once the app is listed there, to F-Droid and Google Play. Development happens on `dev`, the repository's default branch; `main` receives releases by fast-forward and is the only branch CI signs. Store metadata lives in `fastlane/metadata/android/en-US/`; F-Droid reads it from the repository directly, and the same files are uploaded to Play.

## 1. Prepare the release commit

On `dev`:

1. In `android/app/build.gradle.kts`, bump `versionName` and increase `versionCode` by one. `versionCode` must never go down or repeat: Play rejects the upload, and F-Droid pins each build to it.
2. In `CHANGELOG.md`, rename the "Next release" heading to the version and start a fresh "Next release" section above it.
3. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, at most 500 characters (Play's limit), condensed from the changelog section.
4. Refresh the screenshots in `fastlane/metadata/android/en-US/images/` if the UI changed. Play accepts PNG or JPEG without alpha, each side 320 to 3840 px, longest side at most twice the shortest.

## 2. Build and check

```
cd android
./gradlew testDebugUnitTest assembleRelease bundleRelease
```

Outputs: `app/build/outputs/apk/release/patchpilot-v<version>.apk`, signed with the release key from `local.properties`, and `app/build/outputs/bundle/release/app-release.aab`.

Install the release APK, not a debug build, on a phone, connect a real instrument and run the regression test from the debug menu (five taps on the instrument name). R8 runs only for release builds, so a missing keep rule shows up only there.

`./gradlew lintRelease` passes with warnings only and is worth running before a release; CI does not run it.

## 3. Tag and publish on GitHub

Fast-forward `main` to the release commit and push both branch and tag. The push to `main` runs the signed CI build (`android.yml`); the tag push runs `release.yml`, which builds its own signed APK and creates the GitHub Release, using the `## <version>` section of `CHANGELOG.md` as the release notes.

```
git checkout main && git merge --ff-only dev
git tag v<version>
git push origin main v<version>
```

## 4. F-Droid (once listed)

Nothing to do. With `UpdateCheckMode: Tags` in the fdroiddata metadata, the F-Droid bot picks up the new `v*` tag, opens a merge request with the new `versionCode`, and the build server builds it from the tag. A build failure is reported in the fdroiddata issue tracker.

## 5. Google Play (once listed)

Upload `app-release.aab` to the production track in the Play Console (or to a testing track first), paste the changelog entry as the release notes, and roll out. This can later be automated with `fastlane supply` against the same metadata directory.

## Signing

The release key in `android/keystore/patchpilot-release.jks` signs the GitHub APKs. Both the keystore directory and `local.properties` are gitignored and have never been committed. Losing the key means GitHub users can no longer update in place, so keep a backup of the keystore and of `local.properties` outside this machine. The plan is to use the same key as the Play upload key, so that Play-distributed APKs carry the same signature as the GitHub ones; the app-signing key choice on Play is permanent.

CI (`.github/workflows/android.yml`) signs `main` builds with the same key, read from four repository secrets that mirror the `local.properties` keys: `RELEASE_KEYSTORE_BASE64` (the `.jks` file, base64-encoded), `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD`. To set them from a machine that has the keystore and `local.properties`:

```
cd android
gh secret set RELEASE_KEYSTORE_BASE64 --body "$(base64 -i keystore/patchpilot-release.jks)"
for k in RELEASE_STORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD; do
  sed -n "s/^$k=//p" local.properties | gh secret set "$k"
done
```

A push to `main` without these secrets fails at the signing step rather than producing an unsigned APK. Pull-request builds never use the key.
