# FileBrowser

A file browser for several connected file systems (local disks, NFS shares, S3 buckets), built with
Vaadin 25.3 (Flow, Aura theme) on Spring Boot 4.1 and Java 21.

## Run

```bash
mvn
```

Opens on http://localhost:8080. Sign in with any well-formed email and a non-empty password, or
with "Continue with Google", which signs in a demo account.

Production build: `mvn -Pproduction package`, then `java -jar target/file-browser-1.0-SNAPSHOT.jar`.

## What is simulated

There is no backend yet. All of the following lives in the Vaadin session and is lost when it ends:

- Sign-in (`CurrentUser`): no password check, no real Google OAuth.
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
