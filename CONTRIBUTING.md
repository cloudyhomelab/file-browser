# Contributing to FileBrowser

FileBrowser is built with Vaadin 25.3 (Flow, Aura theme) on Spring Boot 4.1 and Java 21, with
SQLite (through Flyway and Spring's `JdbcClient`) for its own data.

## Run

Start the development identity providers, then the app with the `dev` profile:

```bash
docker compose -f environment/dev/docker-compose.yml up -d
```

```bash
mvn -Dspring-boot.run.profiles=dev
```

The app runs on http://localhost:8080. The `dev` profile signs in against the mock provider and
makes `admin@example.com` an admin. `environment/dev/` holds everything the development environment
needs; `environment/prod/` holds deployment samples.

## Sign-in during development

### Mock provider

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

### Pocket ID

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

## Testing with NFS and SMB shares

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

## Build and test

CI runs the same production build that goes into the image:

```bash
mvn -Pproduction clean verify
```

The image is built with Buildx Bake from `Dockerfile` and `docker-bake.hcl`, for amd64 and arm64:

```bash
docker buildx bake
```

On every push, the CI workflow runs the build above; on `main` it then publishes the image to
Docker Hub and signs it.

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
