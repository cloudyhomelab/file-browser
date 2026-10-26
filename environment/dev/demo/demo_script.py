"""The walkthrough recorded by record_demo.py."""

import re
import time
from pathlib import Path

from playwright.sync_api import sync_playwright

# headless recordings have no pointer, so draw one that follows the mouse and shows clicks
CURSOR_JS = """
window.addEventListener('DOMContentLoaded', () => {
  const dot = document.createElement('div');
  dot.style.cssText = 'position:fixed;left:-40px;top:-40px;width:22px;height:22px;margin:-3px 0 0 -3px;'
    + 'z-index:2147483647;pointer-events:none;transition:transform .12s ease;'
    + 'background:no-repeat url("data:image/svg+xml,%3Csvg xmlns=%27http://www.w3.org/2000/svg%27 viewBox=%270 0 24 24%27%3E'
    + '%3Cpath d=%27M3 2l7 19 2.6-7.4L20 11z%27 fill=%27%23111%27 stroke=%27white%27 stroke-width=%271.5%27/%3E%3C/svg%3E")';
  document.documentElement.appendChild(dot);
  addEventListener('mousemove', e => { dot.style.left = e.clientX + 'px'; dot.style.top = e.clientY + 'px'; }, true);
  addEventListener('mousedown', e => {
    dot.style.transform = 'scale(.8)';
    const ring = document.createElement('div');
    ring.style.cssText = `position:fixed;left:${e.clientX - 14}px;top:${e.clientY - 14}px;width:28px;height:28px;`
      + 'border-radius:50%;border:2px solid rgba(0,106,245,.7);z-index:2147483646;pointer-events:none;'
      + 'transition:transform .45s ease,opacity .45s ease';
    document.documentElement.appendChild(ring);
    requestAnimationFrame(() => { ring.style.transform = 'scale(1.8)'; ring.style.opacity = '0'; });
    setTimeout(() => ring.remove(), 500);
  }, true);
  addEventListener('mouseup', () => { dot.style.transform = ''; }, true);
});
"""

# a passkey that always answers with user verification, like a phone or laptop would
AUTHENTICATOR = {"protocol": "ctap2", "transport": "internal", "hasResidentKey": True,
                 "hasUserVerification": True, "isUserVerified": True, "automaticPresenceSimulation": True}

TYPING_DELAY_MS = 55
MOVE_STEPS = 18


def virtual_authenticator(context, page):
    cdp = context.new_cdp_session(page)
    cdp.send("WebAuthn.enable")
    authenticator = cdp.send("WebAuthn.addVirtualAuthenticator", {"options": AUTHENTICATOR})["authenticatorId"]
    return cdp, authenticator


def register_passkey(link, pocket_id, viewport, scratch: Path):
    """Signs the demo user in with a one-time link and registers a passkey on a virtual authenticator."""
    with sync_playwright() as p:
        browser = p.chromium.launch(channel="chrome", headless=True)
        context = browser.new_context(viewport=viewport)
        page = context.new_page()
        cdp, authenticator = virtual_authenticator(context, page)
        page.goto(link)
        page.wait_for_url("**/signup/add-passkey", timeout=15_000)
        page.get_by_role("button", name="Add Passkey").first.click()
        page.wait_for_url(pocket_id + "/settings/**", timeout=15_000)
        page.screenshot(path=str(scratch / "passkey-registered.png"))
        credentials = cdp.send("WebAuthn.getCredentials", {"authenticatorId": authenticator})["credentials"]
        browser.close()
    if not credentials:
        raise RuntimeError("Pocket ID did not register a passkey")
    return credentials[0]


class Demo:

    def __init__(self, page):
        self.page = page

    def pause(self, seconds=1.0):
        self.page.wait_for_timeout(int(seconds * 1000))

    def point(self, locator):
        locator.wait_for(state="visible")
        locator.scroll_into_view_if_needed()
        box = locator.bounding_box()
        x, y = box["x"] + box["width"] / 2, box["y"] + box["height"] / 2
        self.page.mouse.move(x, y, steps=MOVE_STEPS)
        self.pause(0.25)
        return x, y

    def click(self, locator, after=0.8):
        self.point(locator)
        locator.click()
        self.pause(after)

    def double_click(self, locator, after=0.8):
        self.point(locator)
        locator.dblclick()
        self.pause(after)

    def type(self, locator, text, after=0.5):
        self.click(locator, after=0.2)
        locator.press("ControlOrMeta+a")
        locator.press_sequentially(text, delay=TYPING_DELAY_MS)
        self.pause(after)


def sign_in(demo, page, base):
    """The app sends the visitor to Pocket ID, where the virtual passkey answers."""
    page.goto(base + "/")
    page.wait_for_url(lambda url: not url.startswith(base))
    demo.pause(1.5)
    # depending on its session Pocket ID shows a sign-in or a consent screen; each has one button to go on
    proceed = page.get_by_role("button", name=re.compile("^(Sign in|Authenticate|Authorize|Allow)$"))
    deadline = time.time() + 20
    while not page.url.startswith(base) and time.time() < deadline:
        if proceed.count() and proceed.first.is_visible():
            demo.click(proceed.first, after=1)
        page.wait_for_timeout(300)
    page.wait_for_url(base + "/**")
    demo.pause(1.5)


def walkthrough(demo, page, base, files: Path, incoming: Path):
    sign_in(demo, page, base)

    # an admin connects a folder on the server, picked with the folder browser
    demo.click(page.get_by_role("button", name="Add file system").first, after=1)
    demo.type(page.get_by_label("Display name"), files.name, after=0.3)
    demo.type(page.get_by_label("Mount path"), str(files.parent), after=0.6)
    demo.click(page.locator(".fb-field-browse"), after=1.2)
    # the picker opens with the typed folder selected
    demo.click(page.locator(".fb-move-row--selected .fb-caret"), after=0.8)
    demo.click(page.locator(".fb-move-row").filter(has_text=files.name), after=0.8)
    demo.click(page.get_by_role("button", name="Choose", exact=True), after=1.2)
    demo.click(page.get_by_role("button", name="Connect", exact=True), after=2)

    # browse it, in the grid view
    card = lambda name: page.locator(".fb-card").filter(has_text=name)
    demo.click(page.locator(".fb-drive").filter(has_text=files.name), after=2)
    demo.click(page.get_by_role("radio", name="Grid"), after=1.5)
    demo.click(card("Projects"), after=1.2)
    demo.click(card("Website redesign"), after=1.5)

    # auto refresh picks up a file that someone else saves into the folder
    demo.click(page.get_by_role("button", name=re.compile("^Auto refresh")), after=0.8)
    demo.click(page.locator(".fb-menu-item").filter(has_text="Every 5 s"), after=1.5)
    arrived = files / "Projects" / "Website redesign" / "client notes.md"
    with open(arrived, "wb") as f:
        f.truncate(4_300)
    card("client notes.md").wait_for(timeout=10_000)
    demo.pause(2)

    # details and starring
    hero = card("hero.png")
    demo.click(hero, after=1.8)
    demo.point(hero)
    demo.click(hero.locator(".fb-card-actions button").last, after=1)
    demo.click(page.locator(".fb-menu-item").filter(has_text="Add to starred"), after=1.2)

    # search only filters this folder
    search = page.get_by_role("textbox", name="Search this folder")
    demo.type(search, "wire", after=1.5)
    demo.click(search, after=0.2)
    search.fill("")
    demo.pause(1)

    # upload two files
    demo.click(page.get_by_role("button", name="Upload", exact=True).first, after=1)
    with page.expect_file_chooser() as chooser_info:
        demo.click(page.locator(".fb-upload-zone"), after=0.3)
    chooser_info.value.set_files([str(p) for p in sorted(incoming.iterdir())])
    demo.pause(1.5)
    demo.click(page.get_by_role("button", name="Upload 2", exact=True), after=3)
    demo.click(page.locator(".fb-dock-head .fb-icon-btn").last, after=0.8)

    # a new folder, then select both uploads and move them into it together
    demo.click(page.get_by_role("button", name="New folder", exact=True), after=0.8)
    demo.type(page.get_by_role("dialog").get_by_role("textbox"), "Client review", after=0.4)
    page.keyboard.press("Enter")
    demo.pause(1.5)
    for name in sorted(p.name for p in incoming.iterdir()):
        upload = card(name)
        demo.point(upload)
        demo.click(upload.locator(".fb-card-check [role=checkbox]"), after=0.8)
    demo.pause(1.2)
    demo.click(page.locator(".fb-bulkbar-btn").filter(has_text="Move"), after=1.2)
    for folder in ("Projects", "Website redesign"):
        demo.click(page.locator(".fb-move-row").filter(has_text=folder).locator(".fb-caret"), after=0.6)
    demo.click(page.locator(".fb-move-row").filter(has_text="Client review"), after=0.8)
    demo.click(page.get_by_role("button", name="Move here", exact=True), after=1.5)
    demo.click(card("Client review"), after=1.8)

    # starred items across file systems
    demo.click(page.get_by_text("Starred", exact=True).first, after=2.2)

    # back home: the admin menu on a file system
    demo.click(page.get_by_role("button", name="All file systems"), after=1.5)
    demo.click(page.get_by_role("button", name="File system actions", exact=True).first, after=2)
    demo.click(page.locator(".fb-entry-menu-backdrop").first, after=2)


def run(base, viewport, videos: Path, passkey, files: Path, incoming: Path) -> Path:
    with sync_playwright() as p:
        browser = p.chromium.launch(channel="chrome", headless=True)
        context = browser.new_context(viewport=viewport, record_video_dir=str(videos), record_video_size=viewport)
        context.add_init_script(CURSOR_JS)
        page = context.new_page()
        cdp, authenticator = virtual_authenticator(context, page)
        cdp.send("WebAuthn.addCredential", {"authenticatorId": authenticator, "credential": passkey})
        page.set_default_timeout(15_000)
        try:
            walkthrough(Demo(page), page, base, files, incoming)
        finally:
            page.screenshot(path=str(videos / "last.png"))
            context.close()
            browser.close()
        return Path(page.video.path())
