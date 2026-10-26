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

Opens on http://localhost:8080 and redirects to the mock OIDC provider. Enter any username and,
in the claims box, at least an email, e.g. `{"email": "alice@example.com", "name": "Alice"}`.

Sign-in is OIDC only. Without the `dev` profile, configure at least one provider through Spring's
`SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_<ID>_*` and `..._PROVIDER_<ID>_ISSUER_URI` variables;
`FILEBROWSER_OIDC_<ID>_LABEL` and `FILEBROWSER_OIDC_<ID>_ICON` (`google`, `keycloak`, `pocket-id`,
`generic`) set the sign-in button.

Production build: `mvn -Pproduction package`, then `java -jar target/file-browser-1.0-SNAPSHOT.jar`.

## Data

Application data lives in one SQLite file, `./data/filebrowser.db` by default; set
`FILEBROWSER_DATABASE_PATH` to move it. Flyway creates and upgrades the schema at startup.

Backup: copy the file while the app is stopped, or take a consistent copy while it runs:

```bash
sqlite3 data/filebrowser.db "VACUUM INTO 'backup.db'"
```

Restore: stop the app and put the copy in place.

## What is simulated

There is no backend yet. All of the following lives in the Vaadin session and is lost when it ends:

- File systems (`Workspace`): the app starts with none; added ones are not connected to, start
  empty, and their contents live in memory. S3 secrets are discarded.
- Uploads: only the names and sizes of picked or dropped files reach the server, and progress is
  simulated. Downloads only show a notification.

## Layout

- `data/` – model records, JSON-backed reference data (`Catalog`), formatting and sorting.
- `service/` – session state (`Workspace`, `CurrentUser`) and the login guard.
- `ui/` – reusable components; `ui/dialog/` – modal dialogs.
- `views/` – routes: `login`, `` (file systems), `starred`, `fs/:rootId/:path*`.
- `src/main/resources/data/` – file-system types, file types and demo data.
- `src/main/resources/META-INF/resources/` – `styles.css` (design tokens as `--fb-*`, on top of Aura) and the `icons.svg` sprite.
