# Codelab TODO

## To confirm
- [ ] App runs on an emulator against a local HAPI server: Sync, Update, and Search work.
- [ ] Update swaps the cities of `Aaron697` and `Abby752` on the server (PATCH upload).
- [ ] `StringClientParam("name")` with `CONTAINS` matches given and family names, as in
      android-fhir.
- [ ] `patient.gender?.value?.name` gives readable text. Upstream used `gender.display`.
- [ ] The README steps work from a clean clone in Android Studio.
- [ ] Keeping a standalone build is acceptable, given that the root Spotless config does not cover it.

## Pending
- [ ] Keep the README code in step with the starter. The solution exists only in the README.
- [ ] New screenshots of the app, the project in Android Studio, and the Sync button.
- [ ] Bump the engine version in `shared/build.gradle.kts` and the README with each release.
- [ ] Build the codelab in CI.
- [ ] Other platforms: add targets to `shared` and implement `SyncLauncher` for each.
      Desktop, web, and iOS need a sync scheduler. Today it is internal to `engine-app`.
- [ ] Web: document the CORS setup for HAPI.
