# FileBrowser

A file browser for several connected file systems (local disks, NFS shares, S3 buckets), built with
Vaadin 25.3 (Flow, Aura theme) on Spring Boot 4.1 and Java 21.

## Run

Start the development identity providers, then the app with the `dev` profile:

```bash
docker compose -f environment/dev/docker-compose.yml up -d
```

```bash
mvn -Dspring-boot.run.profiles=dev
```

The app runs on http://localhost:8080. The `dev` profile signs in against the mock provider and
makes `admin@example.com` an admin.

## Deploy

`environment/prod/` has a sample `docker-compose.yml` that runs the published image with one OIDC
provider, a volume for the database and one host folder to add as a file system. Copy
`.env.example` to `.env` next to it, fill it in, then:

```bash
docker compose -f environment/prod/docker-compose.yml up -d
```

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
advertises an `end_session_endpoint`.

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

Application data (file systems, user accounts, starred items, view settings) lives in one SQLite
file, `./data/filebrowser.db` by default; set `FILEBROWSER_DATABASE_PATH` to move it. Flyway creates
and upgrades the schema at startup. Files themselves stay where they are on disk.

Backup: copy the file while the app is stopped, or take a consistent copy while it runs:

```bash
sqlite3 data/filebrowser.db "VACUUM INTO 'backup.db'"
```

Restore: stop the app and put the copy in place.

## File systems

Admins add file systems from the app; everyone sees all of them. A file system is a folder on the
server: a local disk, or an NFS/SMB share the OS has mounted. The app detects which (from the mount
type), shows the real capacity, and marks a file system offline when it can't be read in time. Any
absolute path the server process can read may be added; it is added read-only when the server can't
write to it. Deleting is permanent.

Uploads are streamed straight into the folder and appear only when complete; the limit per file is
`FILEBROWSER_MAX_UPLOAD_SIZE` (default `5GB`). Downloading a folder or several items produces a
zip built on the fly.

Each user's starred items and view settings are saved per email. Renaming, moving or deleting an
item in the app updates everyone's stars; stars on items removed outside the app disappear the next
time they're looked up.

### Testing with NFS and SMB shares (development)

`environment/dev/docker-compose.yml` has an opt-in `shares` profile with a Samba server and an NFS v4 server. Their
contents live in the git-ignored `data/shares/`. Mounting needs root and the client tools
(`cifs-utils`, `nfs-common`).

```bash
mkdir -p data/shares/smb data/shares/nfs /tmp/fb-smb /tmp/fb-nfs
```

The NFS server uses the host kernel's NFS server; load its modules on the host first:

```bash
sudo modprobe nfs && sudo modprobe nfsd
```

```bash
docker compose -f environment/dev/docker-compose.yml --profile shares up -d smb nfs
```

```bash
sudo mount -t cifs //127.0.0.1/share /tmp/fb-smb -o username=test,password=test,uid=$(id -u),gid=$(id -g),vers=3.0
```

```bash
sudo mount -t nfs4 127.0.0.1:/ /tmp/fb-nfs
```

Add `/tmp/fb-smb` and `/tmp/fb-nfs` as file systems; their cards show "SMB mount" and "NFS mount"
with the mount source. Unmount before stopping the servers, otherwise access to `/tmp` can stall:

```bash
sudo umount /tmp/fb-smb /tmp/fb-nfs
```

```bash
docker compose -f environment/dev/docker-compose.yml --profile shares stop smb nfs
```

## Layout

- `config/` – settings (`FileBrowserProperties`), database, security and OIDC user loading.
- `data/` – model records, JSON-backed reference data (`Catalog`), formatting and sorting.
- `storage/` – the `StorageProvider` interface and the local-disk implementation.
- `service/` – session state (`Workspace`), file systems (`FileSystemRegistry`, `StorageService`),
  uploads and downloads, the signed-in user (`CurrentUser`), sign-in rules (`SignInPolicy`),
  accounts (`UserAccounts`) and per-user data (`UserData`).
- `ui/` – reusable components; `ui/dialog/` – modal dialogs.
- `views/` – routes: `sign-in`, `` (file systems), `starred`, `fs/:rootId/:path*`.
- `src/main/resources/data/` – file-system types, mount kinds, file types and sign-in error texts.
- `src/main/resources/db/migration/` – Flyway migrations.
- `src/main/resources/META-INF/resources/oidc/` – sign-in button marks.
- `src/main/resources/META-INF/resources/` – `styles.css` (design tokens as `--fb-*`, on top of Aura) and the `icons.svg` sprite.
