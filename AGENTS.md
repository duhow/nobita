# Agents Notes

## IMPORTANT: To start

This repo is now a blank template to begin with a Kotlin project.
To facilitate the setup, there are some preloaded configurations defined, but
you still must create the required missing files, such as MainActivity, XML.
If you know it, change the Application name (MyApp) in `settings.gradle`, `build.gradle`, `Appfile`, XML and README.
Once the base code is established, erase this section "To start" from `AGENTS.md`.

## Commits

Use Conventional Commits in PR title. Common types are feat, fix, chore, refactor, perf, ci, docs.
If using scope, limit scope to two-word max, connected with dash.
Check historic commits to avoid making up new scopes every time.

## Development best practices

- Keep changes focused and minimal to the issue being solved.
- Reuse existing patterns and naming conventions in this codebase.
- Validate changes with available Gradle tasks before opening/merging (`fastlane/gradlew assembleDebug` at minimum).
- If touching UI text, keep user-facing wording clear and consistent.
- Avoid introducing new dependencies unless absolutely necessary.

## IMPORTANT: Layout changes

- `activity_main.xml` exists in **both**:
  - `app/src/main/res/layout/activity_main.xml` (portrait/default)
  - `app/src/main/res/layout-land/activity_main.xml` (landscape/horizontal)
- Any layout modification to this screen **must be applied in both files**.
- Treat portrait and landscape as a paired change to avoid regressions/crashes in horizontal mode.

## Changelogs

- Changelog files live in `fastlane/metadata/android/en-US/changelogs/`.
- Each release has a dedicated numbered file (e.g. `8.txt`) matching the versionCode for that release.
- **Edit the current numbered file** whenever you make user-facing changes (features, fixes, improvements).
- Keep entries concise and written from the user's perspective (e.g. `- Fixed crash when tapping a reset card`).
- Numbered files are permanent per-version snapshots — do **not** edit a file once its release tag has been created.

### Release flow (fully automatic)

1. Edit `<N>.txt` with all changes for the upcoming release.
2. Create the release tag in GitHub — the CI pipeline will automatically:
   - Read `<N>.txt` and use it as the GitHub Release description.
   - Commit a new `<N+1>.txt` placeholder to `main` for the next release.
3. Edit the newly created `<N+1>.txt` for the next release cycle.
