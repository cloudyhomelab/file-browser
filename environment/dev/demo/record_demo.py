"""Records the demo video in media/demo.mp4, its poster frame in media/demo.png and the README's GIF in
media/demo.gif.

Builds the production jar and runs it on port 8081 with the local `demo` profile
(config/application-demo.properties: Pocket ID as the only provider, the demo user as admin) and its
own database, against a seeded folder under /var/tmp/filebrowser-demo. It signs in through Pocket ID with
a virtual passkey and walks through the app in Google Chrome. Needs the dev providers running
(environment/dev/docker-compose.yml) and the packages in requirements.txt.

The passkey is created once with `--register-passkey <one-time sign-in link for the demo user>` and
kept in config/demo-passkey.json (git-ignored).
"""

import json
import os
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
# the folder shown in the video, outside the home folder so its path is short; /var/tmp's other
# folders aren't readable, so the folder picker shows only this one there
FILES = Path("/var/tmp/filebrowser-demo/Studio")
# the database, files to upload and raw recordings, kept apart from the folder shown
DEMO = Path("/tmp/filebrowser-demo-work")
INCOMING = DEMO / "incoming"
VIDEOS = DEMO / "videos"
OUTPUT = ROOT / "media" / "demo.mp4"
POSTER = ROOT / "media" / "demo.png"
# seconds into the video: the file list with the details panel open and the star toast showing
POSTER_AT = 43.5
GIF = ROOT / "media" / "demo.gif"
# smaller and slower than the video, keeping the GIF under GitHub's 10 MB image limit
GIF_WIDTH = 960
GIF_FPS = 8
PORT = 8081
BASE = f"http://localhost:{PORT}"
POCKET_ID = "http://localhost:1411"
PASSKEY = ROOT / "config" / "demo-passkey.json"
VIEWPORT = {"width": 1440, "height": 900}

DAY = 24 * 3600
MB = 1024 * 1024

# path -> (size in bytes, age in days)
TREE = {
    "Projects/Website redesign/brief.md": (6_200, 0.1),
    "Projects/Website redesign/sitemap.pdf": (1_300_000, 2),
    "Projects/Website redesign/wireframes.pdf": (8_400_000, 3),
    "Projects/Website redesign/hero.png": (2_100_000, 1),
    "Projects/Website redesign/copy deck.docx": (184_000, 5),
    "Projects/Mobile app/roadmap.xlsx": (96_000, 9),
    "Projects/Mobile app/onboarding.mp4": (48 * MB, 12),
    "Projects/Mobile app/icon set.zip": (3_600_000, 20),
    "Projects/Annual report 2026/draft v3.docx": (412_000, 1),
    "Projects/Annual report 2026/figures.xlsx": (230_000, 4),
    "Projects/Annual report 2026/cover.jpg": (5_300_000, 6),
    "Design/logo.svg": (14_000, 40),
    "Design/brand guide.pdf": (12 * MB, 60),
    "Design/palette.json": (2_400, 33),
    "Design/Photos/studio-01.jpg": (4_100_000, 70),
    "Design/Photos/studio-02.jpg": (3_900_000, 70),
    "Design/Photos/team.jpg": (6_700_000, 90),
    "Finance/budget 2026.xlsx": (128_000, 8),
    "Finance/invoices.csv": (54_000, 2),
    "Finance/Q3 review.pptx": (7_800_000, 15),
    "Meetings/2026-10-12 standup.md": (3_100, 14),
    "Meetings/kickoff recording.m4a": (22 * MB, 30),
    "README.md": (1_800, 45),
    "notes.txt": (900, 0.3),
}
UPLOADS = {"moodboard.png": 3_200_000, "client feedback.pdf": 640_000}


def seed():
    shutil.rmtree(DEMO, ignore_errors=True)
    shutil.rmtree(FILES.parent, ignore_errors=True)
    now = time.time()
    for relative, (size, age_days) in TREE.items():
        path = FILES / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        # sparse files: realistic sizes in the listing without using the disk space
        with open(path, "wb") as f:
            f.truncate(size)
        stamp = now - age_days * DAY
        os.utime(path, (stamp, stamp))
    # folders take the date of their newest file, deepest first so parents see their children's dates
    for folder in sorted((p for p in FILES.rglob("*") if p.is_dir()), key=lambda p: len(p.parts), reverse=True):
        newest = max(child.stat().st_mtime for child in folder.iterdir())
        os.utime(folder, (newest, newest))
    os.utime(FILES.parent, (now, now))
    INCOMING.mkdir(parents=True)
    for name, size in UPLOADS.items():
        with open(INCOMING / name, "wb") as f:
            f.truncate(size)


def wait_for_port(port, timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        with socket.socket() as s:
            if s.connect_ex(("127.0.0.1", port)) == 0:
                return
        time.sleep(1)
    raise TimeoutError(f"nothing listening on port {port} after {timeout}s")


def build_jar():
    """Builds the committed code in a copy, so a dev server running from target/ is left alone."""
    build = DEMO / "build"
    build.mkdir(parents=True)
    archive = subprocess.run(["git", "archive", "HEAD"], cwd=ROOT, check=True, capture_output=True).stdout
    subprocess.run(["tar", "-x", "-C", str(build)], input=archive, check=True)
    subprocess.run(["mvn", "-q", "-Pproduction", "-DskipTests", "package"], cwd=build, check=True)
    return next(p for p in (build / "target").glob("*.jar"))


def start_app(jar):
    env = dict(os.environ, PORT=str(PORT), FILEBROWSER_DATABASE_PATH=str(DEMO / "filebrowser.db"),
               SPRING_PROFILES_ACTIVE="demo")
    log = open(DEMO / "app.log", "w")
    # run from the project folder so ./config/application-demo.properties is picked up
    app = subprocess.Popen(["java", "-jar", str(jar)], cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT,
                           start_new_session=True)
    wait_for_port(PORT, 120)
    return app


def stop_app(app):
    os.killpg(app.pid, 15)
    app.wait(30)


def convert(webm):
    import imageio_ffmpeg
    OUTPUT.parent.mkdir(exist_ok=True)
    subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), "-y", "-loglevel", "error", "-i", str(webm),
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "28", "-preset", "slow",
                    "-movflags", "+faststart", "-an", str(OUTPUT)], check=True)
    subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), "-y", "-loglevel", "error", "-ss", str(POSTER_AT), "-i", str(OUTPUT),
                    "-frames:v", "1", str(POSTER)], check=True)
    subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), "-y", "-loglevel", "error", "-i", str(OUTPUT),
                    "-vf", f"fps={GIF_FPS},scale={GIF_WIDTH}:-1:flags=lanczos,split[a][b];[a]palettegen=stats_mode=diff[p];"
                           "[b][p]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle",
                    str(GIF)], check=True)


def main():
    from demo_script import run
    if not PASSKEY.exists():
        sys.exit(f"no demo passkey yet: run with --register-passkey <one-time sign-in link> first")
    seed()
    app = start_app(build_jar())
    try:
        webm = run(BASE, VIEWPORT, VIDEOS, json.loads(PASSKEY.read_text()), FILES, INCOMING)
    finally:
        stop_app(app)
    convert(webm)
    print(f"wrote {OUTPUT.relative_to(ROOT)} and {POSTER.relative_to(ROOT)}")


if __name__ == "__main__":
    sys.path.insert(0, str(Path(__file__).parent))
    if sys.argv[1:] == ["--seed-only"]:
        seed()
    elif len(sys.argv) == 3 and sys.argv[1] == "--register-passkey":
        from demo_script import register_passkey
        DEMO.mkdir(parents=True, exist_ok=True)
        credential = register_passkey(sys.argv[2], POCKET_ID, VIEWPORT, DEMO)
        PASSKEY.write_text(json.dumps(credential))
        print(f"saved the demo passkey to {PASSKEY.relative_to(ROOT)}")
    elif sys.argv[1:] == ["--serve"]:
        seed()
        app = start_app(build_jar())
        print(f"demo app on {BASE} with files in {FILES}; Ctrl+C stops it")
        try:
            app.wait()
        except KeyboardInterrupt:
            stop_app(app)
    else:
        main()
