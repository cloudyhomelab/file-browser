# FileBrowser

A web file browser for folders on the server: local disks and NFS/SMB shares the OS has mounted.
Sign-in is through your own OIDC provider, and admins choose which folders everyone can browse.

![A walkthrough of FileBrowser: signing in, adding a folder, browsing, auto refresh, starring, uploading and moving several files at once](media/demo.gif)

## Deploy

`environment/prod/` has a sample `docker-compose.yml` that runs the published image with one OIDC
provider, a volume for the database and one host folder to add as a file system. Copy
`.env.example` to `.env` next to it and fill it in. Create the secret key file next to it too (it
encrypts stored credentials for S3, SFTP and FTP; keep it, and make it readable by the container's user, uid 10001):

```bash
openssl rand -base64 32 > environment/prod/secret-key
```

Then start it:

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
| `FILEBROWSER_ADMIN_EMAILS` | Comma-separated emails that are admins (may add, edit and remove file systems). Matched exactly, ignoring only A–Z case |
| `FILEBROWSER_ALLOWED_DOMAINS` | Optional; if set, the email's domain must be one of these |
| `FILEBROWSER_ALLOWED_EMAILS` | Optional; if set, the email must be one of these (checked after domains) |
| `FILEBROWSER_REQUIRE_VERIFIED_EMAIL` | Default `true`: refuse sign-ins unless the provider sends `email_verified: true`. Turn it off for providers that never send the claim |

Register the app at each provider with the redirect URI `<app-url>/login/oauth2/code/<ID>` and the
post-logout redirect URI `<app-url>`. Signing out also ends the provider session when the provider
advertises an `end_session_endpoint`.

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
write to it.

A file system can also be an S3 bucket, or a folder in one (`s3://bucket` or `s3://bucket/folder`),
on AWS or any S3-compatible store (give its endpoint URL). Enter an access key ID and secret access
key, or leave both blank for a public bucket; the server's own AWS credentials are never used. Keys
are stored encrypted (AES-256-GCM, each bound to its file system) with a key read from the file
named by `FILEBROWSER_SECRET_KEY_FILE`, e.g. a Docker or Kubernetes secret holding 32 random bytes
in base64 (`openssl rand -base64 32`). The key is never taken from an environment variable. It must
be set to add buckets with keys and must stay the same afterwards. Moving or renaming a folder in a bucket copies and then deletes every object in it.

A file system can also be a folder on an SSH server over SFTP (`sftp://host[:port]/folder`) or on an
FTP server (`ftp://host[:port]/folder`). The folder is an absolute path on the server, `/` when
none is given.
- **SFTP** signs in with a user name and either a password or a pasted private key (OpenSSH or PEM,
  optionally with a passphrase). The server's own SSH keys and `~/.ssh` config are never used.
  Usage is shown when the server supports `statvfs` (OpenSSH does).
- **FTP** uses TLS by default: explicit (`AUTH TLS`, port 21) or implicit (port 990). It can also
  run with no encryption, in which case the dialog warns that the password and files cross the
  network in clear text. Anonymous sign-in stores no credentials. Usage isn't reported.
- **Server trust:** the first Connect shows the SFTP host-key fingerprint, or the FTPS certificate's
  fingerprint when the JVM doesn't trust that certificate. The admin confirms it before the file
  system is added. It is stored, and if the server later presents a different one, the file system
  stops connecting until it is removed and added again.
- **Credentials:** the user name, password, private key and passphrase are stored encrypted like
  S3 keys.
- **Copies:** neither protocol copies on the server, so copies are downloaded and uploaded again.
- **FTPS limitation:** servers that require TLS session reuse on data connections (vsftpd's default,
  `require_ssl_reuse=YES`) are not supported yet.

A file system can also be a folder in a Windows or NAS share over SMB
(`smb://host[:port]/share[/folder]`), with no mount on the server.
- **Protocol:** SMB 3 only (3.0, 3.0.2, 3.1.1); servers that offer only SMB 1 or 2 are refused.
- **Sign-in:** NTLM with a user name, password and optional domain, stored encrypted like S3 keys.
  Kerberos and guest access aren't supported; a server that signs the user in as a guest is refused.
- **Security:** every message is signed, which also proves the server knows the password, so there
  is no fingerprint to confirm. Messages are encrypted whenever the server offers it; with "Require
  encryption" on, a server that doesn't is refused.
- **Usage and copies:** usage is the share's size and free space as the server reports them. Copies
  are made by the server.

Admins can rename a file system or change its access from the "⋯" menu on its card; its path can't
change, so to point it elsewhere remove it and add it again. Removing a file system only takes it
off the list: its files stay where they are, and everyone's stars in it are removed. Changes apply at
once, including for people who have it open.

Deleting files and folders in the app is permanent, with no trash: the app doesn't own the storage,
so it couldn't promise that trashed items survive other clients. Deleting a folder asks for its name
to be typed (for several items including a folder, "Delete N items"). The delete then waits
`FILEBROWSER_DELETE_UNDO_SECONDS` seconds (default 5) with an Undo button; the items disappear for the
person deleting them at once and for everyone else when the delete runs. It runs on the server whether
or not the page is still open; if the server stops before then, nothing is deleted. `0` deletes at
once.

Uploads are streamed straight into the folder and appear only when complete; the limit per file is
`FILEBROWSER_MAX_UPLOAD_SIZE` (default `5GB`). Downloading a folder or several items produces a zip built on the fly.

Whole folders can be uploaded by dropping them or with "Choose folder" in the upload dialog; their
structure is kept, including empty folders when dropped (the browser's folder picker doesn't list
those). The folders are created first, then the files are sent. One drop or pick may hold at most
`FILEBROWSER_MAX_FOLDER_UPLOAD_ENTRIES` files and folders (default 10000).

Files can also be uploaded from web links: the server downloads them straight into the folder, under
the name the link's server suggests (or the last part of its path), with the same size limit. Only
`http`/`https` links to public internet addresses are fetched; loopback, link-local (including cloud
metadata addresses) and private networks are refused, redirects included, so links can't be used to
reach services next to the server. To allow private ranges, for example an intranet file server, set
`FILEBROWSER_LINK_UPLOAD_ALLOWED_NETWORKS` to comma-separated CIDRs inside 10.0.0.0/8, 172.16.0.0/12,
192.168.0.0/16 or fc00::/7; any other range stops the app from starting.

Archives can be unpacked as they're uploaded, from the computer or from a link: tick "Extract" for
an archive, or "Decompress" for a single compressed file. Contents go into a new folder named after the archive or into the current folder, and the
archive itself is kept only if asked. Entries that would land outside the folder, links and special
files are skipped. An archive may expand to at most `FILEBROWSER_MAX_EXTRACT_SIZE` (default four
times the upload limit) and `FILEBROWSER_MAX_EXTRACT_ENTRIES` entries (default 10000); going over
stops the extraction and removes what it wrote. The archive is received into the server's temp
folder first, so that needs room for it.

Supported formats, recognised by the end of the file name; each one is covered by the test suite:

| Format | Name endings | Option |
|---|---|---|
| Zip | `.zip` | Extract |
| Tar | `.tar` | Extract |
| Tar + gzip | `.tar.gz`, `.tgz` | Extract |
| Tar + bzip2 | `.tar.bz2`, `.tbz2`, `.tbz` | Extract |
| Tar + xz | `.tar.xz`, `.txz` | Extract |
| Tar + lzip | `.tar.lz`, `.tlz` | Extract |
| 7-Zip | `.7z` | Extract |
| gzip | `.gz` | Decompress |
| bzip2 | `.bz2` | Decompress |
| xz | `.xz` | Decompress |
| lzip | `.lz` | Decompress |

Other formats, such as RAR, Zstandard (`.zst`), LZ4, `.lzma` and `.Z`, upload as ordinary files.

Each user's starred items and view settings are saved per email. Renaming, moving or deleting an
item in the app updates everyone's stars; stars on items removed outside the app disappear the next
time they're looked up.

## Contributing

Running the app locally, the development sign-in providers, test shares and the code layout are in
[CONTRIBUTING.md](CONTRIBUTING.md).

## License

FileBrowser is free software: you can redistribute it and/or modify it under the terms of the GNU
General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. See [`LICENSE`](LICENSE) for the full text.
