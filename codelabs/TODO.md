# Codelab TODO

## To confirm
- [x] App runs on an emulator against a local HAPI server: Sync, Update, and Search work.
- [x] Update swaps the cities of `Aaron697` and `Abby752` on the server (PATCH upload).
- [ ] Desktop, web, and iOS apps run against HAPI: Sync, Update, and Search work. Desktop and web
      Sync and Update are confirmed; iOS has not been run.
- [ ] The web app's database persists in OPFS across reloads.
- [x] iOS: the `iosApp` Xcode project builds with the `:shared:embedAndSignAppleFrameworkForXcode`
      build phase.
- [ ] The README steps work from a clean clone in Android Studio.
- [ ] Keeping a standalone build is acceptable. The root Spotless config covers its `.kt` files
      but not its `.gradle.kts` files.

## Pending
- [ ] Keep the README code in step with the starter. The solution exists only in the README.
- [x] Screenshots of the app on the Android emulator (`images/image6.png` to `image9.png`).
- [ ] Screenshot of the project open in Android Studio.
- [ ] iOS: confirm the steps in `iosApp/README.md` on a real device.
- [ ] Bump the engine version in `shared/build.gradle.kts` and the README with each release, and
      the HAPI image in `docker-compose.yml` from time to time.
- [x] Web: HAPI `v8.12.0-2` allows CORS requests from `http://localhost:8081` by default.
