package org.vaadin.filebrowser.ui;

import java.util.ArrayList;
import java.util.List;

import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.Incoming;
import org.vaadin.filebrowser.data.IncomingFile;
import org.vaadin.filebrowser.data.IncomingFolder;
import org.vaadin.filebrowser.service.UploadTask;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.function.SerializableConsumer;
import com.vaadin.flow.function.SerializableSupplier;
import com.vaadin.flow.server.streams.UploadHandler;

import lombok.Setter;

import tools.jackson.databind.json.JsonMapper;

/**
 * A container that accepts files and folders dragged from the desktop (and optionally opens a file picker on
 * click). Picking only reports names, sizes and folder structure; contents are sent later by {@link #upload},
 * once the user confirmed and name conflicts are resolved. While files hover, the element carries the
 * {@code fb-drag-over} class.
 */
public class FileDropArea extends Div {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Browsers open about this many connections per host over HTTP/1.1; more uploads would starve Vaadin's own requests. */
    private static final int MAX_PARALLEL_UPLOADS = 6;

    // file contents stay in the browser (window.__fbFiles) until uploaded, so any drop area on the page can send them
    private static final String INSTALL_SCRIPT = """
            const el = this, pickOnClick = $0, maxEntries = $1, sends = $2;
            const store = window.__fbFiles || (window.__fbFiles = new Map());
            let depth = 0;
            const hasFiles = e => e.dataTransfer && [...(e.dataTransfer.types || [])].includes('Files');
            const remember = (f, path) => {
              window.__fbFileSeq = (window.__fbFileSeq || 0) + 1;
              const id = 'f' + window.__fbFileSeq;
              store.set(id, f);
              return { id, name: f.name, size: f.size, path };
            };
            const send = picked => {
              const count = picked.files.length
                  + picked.folders.reduce((n, d) => n + 1 + d.dirs.length + d.files.length, 0);
              if (count > maxEntries) { el.$server.tooManyEntries(); return; }
              const meta = {
                files: picked.files.map(f => remember(f, [])),
                folders: picked.folders.map(d => ({ name: d.name, dirs: d.dirs, files: d.files.map(x => remember(x.file, x.path)) }))
              };
              el.$server.filesDropped(JSON.stringify(meta));
            };
            const fileOf = entry => new Promise((resolve, reject) => entry.file(resolve, reject));
            // readEntries returns at most 100 entries per call; an empty batch means the end
            const childrenOf = dir => new Promise((resolve, reject) => {
              const reader = dir.createReader(), all = [];
              const next = () => reader.readEntries(batch => {
                if (!batch.length) { resolve(all); return; }
                all.push(...batch); next();
              }, reject);
              next();
            });
            // stops early past the limit: send() then reports it instead of listing everything
            const walk = async entries => {
              const picked = { files: [], folders: [] };
              let count = 0;
              const into = async (dir, path, folder) => {
                for (const child of await childrenOf(dir)) {
                  if (++count > maxEntries) return;
                  const childPath = [...path, child.name];
                  if (child.isDirectory) { folder.dirs.push(childPath); await into(child, childPath, folder); }
                  else folder.files.push({ file: await fileOf(child), path });
                }
              };
              for (const entry of entries) {
                if (++count > maxEntries) break;
                if (entry.isDirectory) {
                  const folder = { name: entry.name, dirs: [], files: [] };
                  picked.folders.push(folder);
                  await into(entry, [], folder);
                } else {
                  picked.files.push(await fileOf(entry));
                }
              }
              if (count > maxEntries) { el.$server.tooManyEntries(); return null; }
              return picked;
            };
            const dropped = e => {
              // the items are gone once the drop handler returns, so the entries are taken right away
              const entries = [...(e.dataTransfer.items || [])]
                  .filter(i => i.kind === 'file')
                  .map(i => i.webkitGetAsEntry ? i.webkitGetAsEntry() : null);
              if (!entries.length || !entries.every(Boolean) || !entries.some(x => x.isDirectory)) {
                send({ files: [...e.dataTransfer.files], folders: [] });
                return;
              }
              walk(entries).then(picked => picked && send(picked)).catch(() => el.$server.unreadable());
            };
            el.addEventListener('dragenter', e => { if (!hasFiles(e)) return; e.preventDefault(); depth++; el.classList.add('fb-drag-over'); });
            el.addEventListener('dragover', e => { if (hasFiles(e)) e.preventDefault(); });
            el.addEventListener('dragleave', e => { if (!hasFiles(e)) return; depth = Math.max(0, depth - 1); if (!depth) el.classList.remove('fb-drag-over'); });
            el.addEventListener('drop', e => { if (!hasFiles(e)) return; e.preventDefault(); depth = 0; el.classList.remove('fb-drag-over'); dropped(e); });
            if (pickOnClick) {
              el.addEventListener('click', () => {
                const input = document.createElement('input');
                input.type = 'file'; input.multiple = true;
                input.addEventListener('change', () => { if (input.files.length) send({ files: [...input.files], folders: [] }); });
                input.click();
              });
            }
            // the folder picker lists only files, each with its path from the picked folder; empty folders don't appear
            el.__fbPickFolder = () => {
              const input = document.createElement('input');
              input.type = 'file'; input.webkitdirectory = true;
              input.addEventListener('change', () => {
                const folders = new Map();
                for (const f of input.files) {
                  const segments = f.webkitRelativePath.split('/');
                  const name = segments[0];
                  if (!folders.has(name)) folders.set(name, { name, dirs: [], files: [] });
                  folders.get(name).files.push({ file: f, path: segments.slice(1, -1) });
                }
                if (folders.size) send({ files: [], folders: [...folders.values()] });
              });
              input.click();
            };
            // the queue lives on the page, not on this element: a view opened later takes over sending, so
            // leaving the folder view and coming back loses nothing. Each job knows its file system's limit.
            const up = window.__fbUploads || (window.__fbUploads = { lanes: {}, running: {}, known: new Set(), area: null });
            const area = () => up.area && up.area.isConnected ? up.area : null;
            const pump = lane => {
              while (area() && lane.active < lane.limit && lane.waiting.length) {
                const job = lane.waiting.shift();
                const file = store.get(job.clientId);
                if (!file) { area().$server.uploadLost(job.taskId); continue; }
                const base = area().getAttribute('fb-upload-url');
                const xhr = new XMLHttpRequest();
                up.running[job.taskId] = { xhr, job };
                lane.active++;
                xhr.open('POST', base + (base.includes('?') ? '&' : '?') + 'task=' + encodeURIComponent(job.taskId));
                xhr.setRequestHeader('Content-Type', 'application/octet-stream');
                xhr.setRequestHeader('X-Filename', encodeURIComponent(file.name));
                xhr.onloadend = () => {
                  delete up.running[job.taskId];
                  lane.active--;
                  const sent = xhr.status >= 200 && xhr.status < 300;
                  // a request to a view that has gone (its upload URL with it) is sent again once, to the current one
                  if (!sent && !job.cancelled && !job.retried) {
                    job.retried = true;
                    lane.waiting.unshift(job);
                  } else {
                    store.delete(job.clientId);
                    if (!sent && !job.cancelled && area()) area().$server.uploadFailed(job.taskId);
                  }
                  pump(lane);
                };
                xhr.send(file);
              }
            };
            const pumpAll = () => Object.values(up.lanes).forEach(pump);
            if (sends) { up.area = el; pumpAll(); }
            // jobs the page already has are skipped: the server offers its waiting uploads again to each new view
            el.__fbUpload = jobs => {
              for (const job of jobs) {
                if (up.known.has(job.taskId)) continue;
                up.known.add(job.taskId);
                const lane = up.lanes[job.lane] || (up.lanes[job.lane] = { active: 0, waiting: [] });
                lane.limit = job.limit;
                lane.waiting.push(job);
              }
              pumpAll();
            };
            el.__fbCancelUploads = () => {
              Object.values(up.lanes).forEach(lane => { lane.waiting.forEach(job => store.delete(job.clientId)); lane.waiting = []; });
              Object.values(up.running).forEach(r => { r.job.cancelled = true; r.xhr.abort(); });
            };
            """;

    private final Catalog catalog;
    private final boolean pickOnClick;
    private final int maxEntries;
    private final List<Component> folderPickers = new ArrayList<>();
    @Setter
    private SerializableConsumer<Incoming> onFiles = files -> { };
    /** Called with a task id whose file is no longer in the browser (e.g. after a reload). */
    @Setter
    private SerializableConsumer<String> onUploadLost = taskId -> { };
    /** Called with a task id whose file the browser couldn't send, even when it tried again. */
    @Setter
    private SerializableConsumer<String> onUploadFailed = taskId -> { };
    /** The uploads the server still waits for, offered again on attach in case this is a new view in the same page. */
    @Setter
    private SerializableSupplier<List<UploadTask>> waiting = List::of;
    private boolean sends;

    private record DroppedFile(String id, String name, long size, List<String> path) {
    }

    private record DroppedFolder(String name, List<List<String>> dirs, List<DroppedFile> files) {
    }

    private record Dropped(List<DroppedFile> files, List<DroppedFolder> folders) {
    }

    private record UploadJob(String taskId, String clientId, String lane, int limit) {
    }

    /** @param maxEntries the most files and folders one drop or pick may hold, at every depth */
    public FileDropArea(Catalog catalog, boolean pickOnClick, int maxEntries) {
        this.catalog = catalog;
        this.pickOnClick = pickOnClick;
        this.maxEntries = maxEntries;
    }

    /** Where {@link #upload} sends file contents; the handler reads the task id from the {@code task} parameter. */
    public void setUploadHandler(UploadHandler handler) {
        getElement().setAttribute("fb-upload-url", handler);
        sends = true;
    }

    /**
     * Clicking {@code trigger} opens the browser's folder picker. The click is handled in the browser: a picker
     * opened after a round trip to the server may be refused for lacking a user gesture.
     */
    public void addFolderPicker(Component trigger) {
        folderPickers.add(trigger);
    }

    /** Sends the tasks' files, no more at a time per file system than it has connections for streams. */
    public void upload(List<UploadTask> tasks) {
        // files from web links are downloaded by the server; only the browser's own files are sent from here
        final var jobs = tasks.stream()
                .filter(t -> t.getClientId() != null)
                .map(t -> new UploadJob(t.getId(), t.getClientId(), t.getRoot().id(),
                        Math.clamp(t.getRoot().options().streamConnections(), 1, MAX_PARALLEL_UPLOADS)))
                .toList();
        if (jobs.isEmpty()) {
            return;
        }
        final var json = JSON.writeValueAsString(jobs);
        getElement().executeJs("this.__fbUpload(JSON.parse($0))", json);
    }

    public void cancelUploads() {
        getElement().executeJs("this.__fbCancelUploads && this.__fbCancelUploads()");
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        getElement().executeJs(INSTALL_SCRIPT, pickOnClick, maxEntries, sends);
        // stopPropagation: a trigger inside a click-to-browse area would open the file picker too
        folderPickers.forEach(trigger -> getElement().executeJs(
                "$0.addEventListener('click', e => { e.stopPropagation(); this.__fbPickFolder(); })", trigger));
        if (sends) {
            final var stillWaiting = waiting.get();
            upload(stillWaiting);
        }
    }

    // called from the browser
    @SuppressWarnings("PMD.UnusedPrivateMethod")
    @ClientCallable
    private void filesDropped(String json) {
        final var dropped = JSON.readValue(json, Dropped.class);
        final var files = dropped.files().stream().map(this::incoming).toList();
        final var folders = dropped.folders().stream()
                .map(d -> {
                    final var inside = d.files().stream().map(this::incoming).toList();
                    return IncomingFolder.of(d.name(), d.dirs(), inside);
                })
                .toList();
        final var incoming = new Incoming(files, folders);
        // the browser checks too, but only the server's count can be trusted
        if (incoming.entryCount() > maxEntries) {
            tooManyEntries();
            return;
        }
        if (!incoming.isEmpty()) {
            onFiles.accept(incoming);
        }
    }

    private IncomingFile incoming(DroppedFile dropped) {
        final var type = catalog.typeForFileName(dropped.name());
        final var file = new IncomingFile(dropped.id(), dropped.name(), type, dropped.size());
        return dropped.path() == null ? file : file.inFolder(dropped.path());
    }

    // called from the browser
    @SuppressWarnings("PMD.UnusedPrivateMethod")
    @ClientCallable
    private void tooManyEntries() {
        Notices.error("At most " + maxEntries + " files and folders can be uploaded at once");
    }

    // called from the browser
    @SuppressWarnings("PMD.UnusedPrivateMethod")
    @ClientCallable
    private void unreadable() {
        Notices.error("The dropped folder couldn't be read");
    }

    // called from the browser
    @SuppressWarnings("PMD.UnusedPrivateMethod")
    @ClientCallable
    private void uploadLost(String taskId) {
        onUploadLost.accept(taskId);
    }

    // called from the browser
    @SuppressWarnings("PMD.UnusedPrivateMethod")
    @ClientCallable
    private void uploadFailed(String taskId) {
        onUploadFailed.accept(taskId);
    }
}
