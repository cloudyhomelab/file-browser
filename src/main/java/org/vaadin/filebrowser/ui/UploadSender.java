package org.vaadin.filebrowser.ui;

import java.util.List;

import org.vaadin.filebrowser.service.UploadTask;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.dom.Style;
import com.vaadin.flow.function.SerializableConsumer;
import com.vaadin.flow.function.SerializableSupplier;
import com.vaadin.flow.server.streams.UploadHandler;

import lombok.Setter;

import tools.jackson.databind.json.JsonMapper;

/**
 * Sends the contents of files picked or dropped on a {@link FileDropArea}, which stay in the browser until then. It
 * belongs in a layout that outlives the views, so uploads keep going while the user browses elsewhere in the app.
 */
public class UploadSender extends Div {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Browsers open about this many connections per host over HTTP/1.1; more uploads would starve Vaadin's own requests. */
    private static final int MAX_PARALLEL_UPLOADS = 6;

    // the queue lives on the page, so a sender attached after a reattach takes over what an earlier one left
    private static final String INSTALL_SCRIPT = """
            const el = this;
            const store = window.__fbFiles || (window.__fbFiles = new Map());
            const up = window.__fbUploads || (window.__fbUploads = { lanes: {}, running: {}, known: new Set(), sender: null });
            up.sender = el;
            const sender = () => up.sender && up.sender.isConnected ? up.sender : null;
            const pump = lane => {
              while (sender() && lane.active < lane.limit && lane.waiting.length) {
                const job = lane.waiting.shift();
                const file = store.get(job.clientId);
                if (!file) { sender().$server.uploadLost(job.taskId); continue; }
                const base = sender().getAttribute('fb-upload-url');
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
                  if (!sent && !job.cancelled && !job.retried) {
                    job.retried = true;
                    lane.waiting.unshift(job);
                  } else {
                    store.delete(job.clientId);
                    if (!sent && !job.cancelled && sender()) sender().$server.uploadFailed(job.taskId);
                  }
                  pump(lane);
                };
                xhr.send(file);
              }
            };
            const pumpAll = () => Object.values(up.lanes).forEach(pump);
            // the server offers its waiting uploads again on every attach; jobs the page already has are skipped
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
            pumpAll();
            """;

    /** Called with a task id whose file is no longer in the browser (e.g. after a reload). */
    @Setter
    private SerializableConsumer<String> onUploadLost = taskId -> { };
    /** Called with a task id whose file the browser couldn't send, even when it tried again. */
    @Setter
    private SerializableConsumer<String> onUploadFailed = taskId -> { };
    /** The uploads the server still waits for, offered again on attach: after a reload the browser reports them lost. */
    @Setter
    private SerializableSupplier<List<UploadTask>> waiting = List::of;

    private record UploadJob(String taskId, String clientId, String lane, int limit) {
    }

    /** @param handler receives each file's contents; it reads the task id from the {@code task} parameter */
    public UploadSender(UploadHandler handler) {
        getElement().setAttribute("fb-upload-url", handler);
        // not setVisible(false): Vaadin ignores browser calls to invisible components
        getStyle().setDisplay(Style.Display.NONE);
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
        getElement().executeJs(INSTALL_SCRIPT);
        final var stillWaiting = waiting.get();
        upload(stillWaiting);
    }

    // called from the browser; not private, so PMD doesn't take it for unused
    @ClientCallable
    void uploadLost(String taskId) {
        onUploadLost.accept(taskId);
    }

    // called from the browser; not private, so PMD doesn't take it for unused
    @ClientCallable
    void uploadFailed(String taskId) {
        onUploadFailed.accept(taskId);
    }
}
