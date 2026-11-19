This is a Kotlin Multiplatform project targeting Desktop (JVM).

- [/shared](./shared/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./shared/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [iosMain](./shared/src/iosMain/kotlin) folder would be the right place for such calls.
    Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./shared/src/jvmMain/kotlin)
    folder is the appropriate location.

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and options:

- Desktop app:
  - Hot reload: `./gradlew :desktopApp:hotRun --auto`
  - Standard run: `./gradlew :desktopApp:run`

### Supabase

Set `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY` in the environment of your IDE run configuration or shell. Use the same project and publishable key as the [Luma Store website](https://github.com/FreetimeMaker/Luma-Store-Website). The website's `NEXT_PUBLIC_SUPABASE_URL` and `NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY` names also work as fallbacks. The app does not read `.env.local` automatically.

`LumaSupabase.client` initializes Auth, PostgREST (the PostgreSQL Data API), and Storage on first use. The existing database and Storage access policies still apply. Never ship a service-role or secret key with the desktop app: privileged operations such as creating signed APK upload URLs stay on the website server. Desktop sign-in supports GitHub, GitLab, and Codeberg. Enable those providers in Supabase and add `http://127.0.0.1:*/oauth/callback/*` to the redirect allowlist. Desktop sessions are kept in memory.

The desktop app reads the catalog, submissions, status history, profile, and funding from Supabase, as the website does. Lifetime download statistics try the website API first and use the authenticated Supabase aggregate on network outages, HTTP 5xx, or Vercel `402 DEPLOYMENT_DISABLED`. Authentication and authorization failures do not trigger fallback. Saving drafts and deleting or archiving submissions require the website API; the app does not retry those operations as direct database writes.

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Desktop tests: `./gradlew :shared:jvmTest`

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…
