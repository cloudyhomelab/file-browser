package org.vaadin.filebrowser.ui;

import java.util.List;

import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.IncomingFile;
import org.vaadin.filebrowser.service.UploadTask;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.function.SerializableConsumer;
import com.vaadin.flow.server.streams.UploadHandler;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A container that accepts files dragged from the desktop (and optionally opens a file picker on
 * click). Picking only reports names and sizes; contents are sent later by {@link #upload}, once the
 * user confirmed and name conflicts are resolved. While files hover, the element carries the
 * {@code fb-drag-over} class.
 */
public class FileDropArea extends Div {

    private record DroppedFile(String id, String name, long size) {
    }

    private record UploadJob(String taskId, String clientId) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    // file contents stay in the browser (window.__fbFiles) until uploaded, so any drop area on the page can send them
    private static final String INSTALL_SCRIPT = """
            const el = this, pickOnClick = $0;
            const store = window.__fbFiles || (window.__fbFiles = new Map());
            let depth = 0;
            const hasFiles = e => e.dataTransfer && [...(e.dataTransfer.types || [])].includes('Files');
            const send = files => {
              const meta = [...files].map(f => {
                window.__fbFileSeq = (window.__fbFileSeq || 0) + 1;
                const id = 'f' + window.__fbFileSeq;
                store.set(id, f);
                return { id, name: f.name, size: f.size };
              });
              el.$server.filesDropped(JSON.stringify(meta));
            };
            el.addEventListener('dragenter', e => { if (!hasFiles(e)) return; e.preventDefault(); depth++; el.classList.add('fb-drag-over'); });
            el.addEventListener('dragover', e => { if (hasFiles(e)) e.preventDefault(); });
            el.addEventListener('dragleave', e => { if (!hasFiles(e)) return; depth = Math.max(0, depth - 1); if (!depth) el.classList.remove('fb-drag-over'); });
            el.addEventListener('drop', e => { if (!hasFiles(e)) return; e.preventDefault(); depth = 0; el.classList.remove('fb-drag-over'); send(e.dataTransfer.files); });
            if (pickOnClick) {
              el.addEventListener('click', () => {
                const input = document.createElement('input');
                input.type = 'file'; input.multiple = true;
                input.addEventListener('change', () => { if (input.files.length) send(input.files); });
                input.click();
              });
            }
            el.__fbXhrs = {};
            el.__fbUpload = jobs => {
              const base = el.getAttribute('fb-upload-url');
              for (const job of jobs) {
                const file = store.get(job.clientId);
                if (!file) { el.$server.uploadLost(job.taskId); continue; }
                const xhr = new XMLHttpRequest();
                el.__fbXhrs[job.taskId] = xhr;
                xhr.open('POST', base + (base.includes('?') ? '&' : '?') + 'task=' + encodeURIComponent(job.taskId));
                xhr.setRequestHeader('Content-Type', 'application/octet-stream');
                xhr.setRequestHeader('X-Filename', encodeURIComponent(file.name));
                xhr.onloadend = () => { delete el.__fbXhrs[job.taskId]; store.delete(job.clientId); };
                xhr.send(file);
              }
            };
            el.__fbCancelUploads = () => { Object.values(el.__fbXhrs).forEach(x => x.abort()); el.__fbXhrs = {}; };
            """;

    private final Catalog catalog;
    private final boolean pickOnClick;
    private SerializableConsumer<List<IncomingFile>> onFiles = files -> { };
    private SerializableConsumer<String> onUploadLost = taskId -> { };

    public FileDropArea(Catalog catalog, boolean pickOnClick) {
        this.catalog = catalog;
        this.pickOnClick = pickOnClick;
    }

    public void onFiles(SerializableConsumer<List<IncomingFile>> handler) {
        this.onFiles = handler;
    }

    /** Where {@link #upload} sends file contents; the handler reads the task id from the {@code task} parameter. */
    public void setUploadHandler(UploadHandler handler) {
        getElement().setAttribute("fb-upload-url", handler);
    }

    /** Called with a task id whose file is no longer in the browser (e.g. after a reload). */
    public void onUploadLost(SerializableConsumer<String> handler) {
        this.onUploadLost = handler;
    }

    public void upload(List<UploadTask> tasks) {
        final var jobs = tasks.stream().map(t -> new UploadJob(t.id(), t.clientId())).toList();
        final var json = JSON.writeValueAsString(jobs);
        getElement().executeJs("this.__fbUpload(JSON.parse($0))", json);
    }

    public void cancelUploads() {
        getElement().executeJs("this.__fbCancelUploads && this.__fbCancelUploads()");
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        getElement().executeJs(INSTALL_SCRIPT, pickOnClick);
    }

    @ClientCallable
    private void filesDropped(String json) {
        final var listType = new TypeReference<List<DroppedFile>>() { };
        final var dropped = JSON.readValue(json, listType);
        final var files = dropped.stream()
                .map(f -> {
                    final var type = catalog.typeForFileName(f.name());
                    return new IncomingFile(f.id(), f.name(), type, f.size());
                })
                .toList();
        if (!files.isEmpty()) {
            onFiles.accept(files);
        }
    }

    @ClientCallable
    private void uploadLost(String taskId) {
        onUploadLost.accept(taskId);
    }
}
