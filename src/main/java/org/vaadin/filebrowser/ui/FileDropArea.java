package org.vaadin.filebrowser.ui;

import java.util.ArrayList;
import java.util.List;

import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.Incoming;
import org.vaadin.filebrowser.data.IncomingFile;
import org.vaadin.filebrowser.data.IncomingFolder;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.function.SerializableConsumer;

import lombok.Setter;

import tools.jackson.databind.json.JsonMapper;

/**
 * A container that accepts files and folders dragged from the desktop (and optionally opens a file picker on
 * click). Picking only reports names, sizes and folder structure; contents are sent later by {@link UploadSender},
 * once the user confirmed and name conflicts are resolved. While files hover, the element carries the
 * {@code fb-drag-over} class.
 */
public class FileDropArea extends Div {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    // file contents stay in the browser (window.__fbFiles) until uploaded, when UploadSender sends them
    private static final String INSTALL_SCRIPT = """
            const el = this, pickOnClick = $0, maxEntries = $1;
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
            """;

    private final Catalog catalog;
    private final boolean pickOnClick;
    private final int maxEntries;
    private final List<Component> folderPickers = new ArrayList<>();
    @Setter
    private SerializableConsumer<Incoming> onFiles = files -> { };

    private record DroppedFile(String id, String name, long size, List<String> path) {
    }

    private record DroppedFolder(String name, List<List<String>> dirs, List<DroppedFile> files) {
    }

    private record Dropped(List<DroppedFile> files, List<DroppedFolder> folders) {
    }

    /** @param maxEntries the most files and folders one drop or pick may hold, at every depth */
    public FileDropArea(Catalog catalog, boolean pickOnClick, int maxEntries) {
        this.catalog = catalog;
        this.pickOnClick = pickOnClick;
        this.maxEntries = maxEntries;
    }

    /**
     * Clicking {@code trigger} opens the browser's folder picker. The click is handled in the browser: a picker
     * opened after a round trip to the server may be refused for lacking a user gesture.
     */
    public void addFolderPicker(Component trigger) {
        folderPickers.add(trigger);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        getElement().executeJs(INSTALL_SCRIPT, pickOnClick, maxEntries);
        // stopPropagation: a trigger inside a click-to-browse area would open the file picker too
        folderPickers.forEach(trigger -> getElement().executeJs(
                "$0.addEventListener('click', e => { e.stopPropagation(); this.__fbPickFolder(); })", trigger));
    }

    // called from the browser; not private, so PMD doesn't take it for unused
    @ClientCallable
    void filesDropped(String json) {
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

    // called from the browser; not private, so PMD doesn't take it for unused
    @ClientCallable
    void tooManyEntries() {
        Notices.error("At most " + maxEntries + " files and folders can be uploaded at once");
    }

    // called from the browser; not private, so PMD doesn't take it for unused
    @ClientCallable
    void unreadable() {
        Notices.error("The dropped folder couldn't be read");
    }
}
