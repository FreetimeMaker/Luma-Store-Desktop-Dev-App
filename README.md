Luma Store Developer Console is a desktop (JVM) app for managing your own Luma Store submissions.

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and options:

- Desktop app:
  - Hot reload: `./gradlew :desktopApp:hotRun --auto`
  - Standard run: `./gradlew :desktopApp:run`

### Supabase

`LumaSupabase.client` initializes Auth, PostgREST (the PostgreSQL Data API), and Storage on first use. The existing database and Storage access policies still apply. Never ship a service-role or secret key with the desktop app: privileged operations such as creating signed APK upload URLs stay on the website server. Desktop sign-in supports GitHub, GitLab, and Codeberg. Enable those providers in Supabase and add `http://127.0.0.1:*/oauth/callback/*` to the redirect allowlist. The SDK saves desktop sessions in local Java Preferences (not an OS keychain) and restores them on startup. Sign out to clear the saved session.

The desktop app reads submissions, status history, profile, and funding from Supabase, as the website does. Lifetime download statistics try the website API first and use the authenticated Supabase aggregate on network outages, HTTP 5xx, or Vercel `402 DEPLOYMENT_DISABLED`. Authentication and authorization failures do not trigger fallback. Saving drafts and deleting or archiving submissions require the website API; the app does not retry those operations as direct database writes.

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Desktop tests: `./gradlew :shared:jvmTest`
