# Codelab TODO

## To confirm
- [ ] App runs on an emulator against a local HAPI server: Sync, Update, and Search work.
- [ ] Update swaps the cities of `Aaron697` and `Abby752` on the server (PATCH upload).
- [ ] Desktop, web, and iOS apps run against HAPI: Sync, Update, and Search work.
- [ ] The web app's database persists in OPFS across reloads.
- [ ] iOS: the `iosApp` Xcode project builds with the `:shared:embedAndSignAppleFrameworkForXcode`
      build phase.
- [ ] The README steps work from a clean clone in Android Studio.
- [ ] Keeping a standalone build is acceptable. The root Spotless config covers its `.kt` files
      but not its `.gradle.kts` files.

## Pending
- [ ] Keep the README code in step with the starter. The solution exists only in the README.
- [ ] New screenshots of the app, the project in Android Studio, and the Sync button.
- [ ] Bump the engine version in `shared/build.gradle.kts` and the README with each release.
- [ ] Web: document how to turn on CORS for `http://localhost:8081` in HAPI, if it is off by default.
