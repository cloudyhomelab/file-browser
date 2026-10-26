# FileBrowser

A file browser for several connected file systems (local disks, NFS shares, S3 buckets), built with
Vaadin 25.3 (Flow, Aura theme) on Spring Boot 4.1 and Java 21.

## Run

Start the development identity providers, then the app with the `dev` profile:

```bash
docker compose up -d
```

```bash
mvn -Dspring-boot.run.profiles=dev
```

The app runs on http://localhost:8080. The `dev` profile signs in against the mock provider and
makes `admin@example.com` an admin.

## Sign-in

Sign-in is OIDC only: the app has no passwords or accounts of its own and identifies users by the
`email` claim, whichever provider they come from. With one provider configured, sign-in goes
straight to it; with several, a chooser shows one button per provider.

### Configuration

Providers use Spring's standard client registration properties; `<ID>` is any name you pick.

| Variable | Purpose |
| --- | --- |
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_<ID>_CLIENT_ID` | Client id at the provider |
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_<ID>_CLIENT_SECRET` | Client secret |
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_<ID>_SCOPE` | `openid,email,profile` |
| `SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_<ID>_ISSUER_URI` | Issuer URL (its `/.well-known/openid-configuration` must be reachable at startup) |
| `FILEBROWSER_OIDC_<ID>_LABEL` | Button text: "Continue with <label>" |
| `FILEBROWSER_OIDC_<ID>_ICON` | `google`, `keycloak`, `pocket-id` or `generic` |
| `FILEBROWSER_ADMIN_EMAILS` | Comma-separated emails that are admins (may add file systems) |
| `FILEBROWSER_ALLOWED_DOMAINS` | Optional; if set, the email's domain must be one of these |
| `FILEBROWSER_ALLOWED_EMAILS` | Optional; if set, the email must be one of these (checked after domains) |
| `FILEBROWSER_REQUIRE_VERIFIED_EMAIL` | Default `true`: refuse sign-ins whose `email_verified` claim is `false` |

Register the app at each provider with the redirect URI `<app-url>/login/oauth2/code/<ID>` and the
post-logout redirect URI `<app-url>`. Signing out also ends the provider session when the provider
supports it (Keycloak and Pocket ID do, Google does not).

### Mock provider (development)

The mock's sign-in page takes any username plus an optional claims JSON. Paste one of these into
the claims box:

| Case | Claims |
| --- | --- |
| Admin | `{"email": "admin@example.com", "name": "Ada Admin"}` |
| Regular user | `{"email": "alice@example.com", "name": "Alice Example"}` |
| Unverified email (refused) | `{"email": "alice@example.com", "email_verified": false}` |
| No email (refused) | `{}` |

To try the allow-list, set e.g. `FILEBROWSER_ALLOWED_DOMAINS=example.com` and sign in with
`{"email": "x@other.org"}`.

### Pocket ID (development)

Pocket ID runs on http://localhost:1411 and signs in with passkeys, so it is set up once by hand:

1. Open http://localhost:1411/setup and create the admin account with a passkey.
2. Under OIDC clients, add a client with callback URL
   `http://localhost:8080/login/oauth2/code/pocket-id` and logout callback URL
   `http://localhost:8080`.
3. Put its credentials in `config/application-dev.properties` (git-ignored):

   ```properties
   spring.security.oauth2.client.registration.pocket-id.client-id=<client id>
   spring.security.oauth2.client.registration.pocket-id.client-secret=<client secret>
   spring.security.oauth2.client.registration.pocket-id.scope=openid,email,profile
   spring.security.oauth2.client.provider.pocket-id.issuer-uri=http://localhost:1411
   filebrowser.oidc.pocket-id.label=Pocket ID
   filebrowser.oidc.pocket-id.icon=pocket-id
   ```

4. Restart the app: the chooser now offers both providers.

## Data

Application data lives in one SQLite file, `./data/filebrowser.db` by default; set
`FILEBROWSER_DATABASE_PATH` to move it. Flyway creates and upgrades the schema at startup.

Backup: copy the file while the app is stopped, or take a consistent copy while it runs:

```bash
sqlite3 data/filebrowser.db "VACUUM INTO 'backup.db'"
```

Restore: stop the app and put the copy in place.

## What is simulated

Sign-in and user accounts are real; file storage is not yet. The following lives in the Vaadin
session and is lost when it ends:

- File systems (`Workspace`): the app starts with none; added ones are not connected to, start
  empty, and their contents live in memory. S3 secrets are discarded.
- Uploads: only the names and sizes of picked or dropped files reach the server, and progress is
  simulated. Downloads only show a notification.

## Layout

- `config/` – settings (`FileBrowserProperties`), database, security and OIDC user loading.
- `data/` – model records, JSON-backed reference data (`Catalog`), formatting and sorting.
- `service/` – session state (`Workspace`), the signed-in user (`CurrentUser`), sign-in rules
  (`SignInPolicy`) and accounts (`UserAccounts`).
- `ui/` – reusable components; `ui/dialog/` – modal dialogs.
- `views/` – routes: `sign-in`, `` (file systems), `starred`, `fs/:rootId/:path*`.
- `src/main/resources/data/` – file-system types, file types, sign-in error texts and demo data.
- `src/main/resources/db/migration/` – Flyway migrations.
- `src/main/resources/META-INF/resources/oidc/` – sign-in button marks.
- `src/main/resources/META-INF/resources/` – `styles.css` (design tokens as `--fb-*`, on top of Aura) and the `icons.svg` sprite.
