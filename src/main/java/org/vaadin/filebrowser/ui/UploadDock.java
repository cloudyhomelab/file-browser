package org.vaadin.filebrowser.ui;

import java.util.ArrayList;
import java.util.List;

import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.Formats;
import org.vaadin.filebrowser.service.TransferTask;
import org.vaadin.filebrowser.service.UploadTask;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.function.SerializableConsumer;
import com.vaadin.flow.function.SerializableRunnable;

/** Bottom-right panel listing uploads and transfers with their progress; dismissing it cancels unfinished ones. */
public class UploadDock extends Composite<Div> {

    private static final int GLYPH_LABEL_CHARS = 3;

    private final Catalog catalog;
    private final SerializableRunnable onDismiss;
    private final SerializableConsumer<TransferTask> onCancel;
    private boolean collapsed;

    public UploadDock(Catalog catalog, SerializableRunnable onDismiss, SerializableConsumer<TransferTask> onCancel) {
        this.catalog = catalog;
        this.onDismiss = onDismiss;
        this.onCancel = onCancel;
        getContent().setClassName("fb-dock");
        getContent().setVisible(false);
    }

    public void show(List<UploadTask> uploads, List<TransferTask> transfers) {
        final var div = getContent();
        div.removeAll();
        div.setVisible(!uploads.isEmpty() || !transfers.isEmpty());
        if (uploads.isEmpty() && transfers.isEmpty()) {
            return;
        }
        div.getElement().getClassList().set("fb-dock--collapsed", collapsed);
        div.add(head(uploads, transfers));
        if (collapsed) {
            return;
        }
        final var list = Ui.div("fb-dock-list");
        uploads.forEach(upload -> list.add(uploadRow(upload)));
        transfers.forEach(transfer -> list.add(transferRow(transfer)));
        div.add(list);
    }

    /** The title, which folds the dock, and the button that cancels everything or dismisses it. */
    private Div head(List<UploadTask> uploads, List<TransferTask> transfers) {
        final var activeTransfers = (int) transfers.stream().filter(t -> !t.finished()).count();
        final var active = (int) uploads.stream().filter(u -> !u.finished()).count() + activeTransfers;
        final var caret = new Icon("angle-down");
        caret.addClassName("fb-dock-caret");
        final var titleLabel = Ui.span("fb-dock-title", title(uploads, transfers));
        final var dismiss = Ui.iconButton("cross", active > 0 ? "Cancel all" : "Dismiss", onDismiss);
        final var head = Ui.div("fb-dock-head", titleLabel, caret, dismiss);
        head.addClickListener(e -> {
            collapsed = !collapsed;
            show(uploads, transfers);
        });
        return head;
    }

    private Div uploadRow(UploadTask upload) {
        final var nameText = new Span(upload.getName());
        final var name = Ui.div("fb-dock-name", nameText);
        final var body = Ui.div("fb-dock-body", name, uploadStatus(upload));
        final var row = Ui.div("fb-dock-row", uploadBadge(upload), body);
        if (!upload.finished()) {
            final var percent = Ui.span("fb-mono fb-dock-pct", upload.progress() + "%");
            row.add(percent);
        }
        return row;
    }

    /** A check when done, a warning when failed, otherwise the start of the file type's label. */
    private Span uploadBadge(UploadTask upload) {
        final var badge = Ui.span("fb-dock-glyph fb-type-" + upload.getType());
        if (upload.complete()) {
            final var checkIcon = new Icon("check");
            badge.add(checkIcon);
        } else if (upload.getState() == UploadTask.State.FAILED) {
            final var warnIcon = new Icon("warn");
            badge.add(warnIcon);
        } else {
            final var info = catalog.fileType(upload.getType());
            final var label = info.label().isEmpty() ? "•" : info.label();
            final var shortLabel = label.substring(0, Math.min(GLYPH_LABEL_CHARS, label.length()));
            badge.add(shortLabel);
        }
        return badge;
    }

    private static Div uploadStatus(UploadTask upload) {
        if (upload.complete()) {
            final var sizeText = upload.getSummary() != null ? upload.getSummary()
                    : Formats.size(upload.knownSize()) + " · Done";
            return line("fb-dock-done", sizeText);
        }
        if (upload.getState() == UploadTask.State.FAILED) {
            return line("fb-dock-failed", upload.getError());
        }
        if (upload.getState() == UploadTask.State.EXTRACTING) {
            return line("fb-dock-done", "Extracting…");
        }
        final var fill = Ui.div("fb-dock-fill");
        fill.getStyle().setWidth(upload.progress() + "%");
        return Ui.div("fb-dock-bar", fill);
    }

    private static String title(List<UploadTask> uploads, List<TransferTask> transfers) {
        final var activeUploads = (int) uploads.stream().filter(u -> !u.finished()).count();
        final var activeTransfers = (int) transfers.stream().filter(t -> !t.finished()).count();
        if (activeUploads > 0 || activeTransfers > 0) {
            return runningTitle(activeUploads, activeTransfers);
        }
        final var parts = new ArrayList<String>();
        if (!uploads.isEmpty()) {
            parts.add(uploadsOutcome(uploads));
        }
        if (!transfers.isEmpty()) {
            parts.add(transfersOutcome(transfers));
        }
        return String.join(" · ", parts);
    }

    private static String runningTitle(int activeUploads, int activeTransfers) {
        final var uploading = activeUploads > 0 ? "Uploading " + Formats.plural(activeUploads, "item") : null;
        final var transferring = activeTransfers > 0 ? Formats.plural(activeTransfers, "transfer") + " running" : null;
        return (uploading == null ? transferring : transferring == null ? uploading
                : uploading + ", " + transferring) + "…";
    }

    private static String uploadsOutcome(List<UploadTask> uploads) {
        final var failed = (int) uploads.stream().filter(u -> u.getState() == UploadTask.State.FAILED).count();
        final var done = (int) uploads.stream().filter(UploadTask::complete).count();
        return Formats.plural(done, "upload") + " complete" + (failed > 0 ? ", " + failed + " failed" : "");
    }

    private static String transfersOutcome(List<TransferTask> transfers) {
        final var failed = (int) transfers.stream().filter(t -> t.getState() == TransferTask.State.FAILED).count();
        final var done = (int) transfers.stream().filter(t -> t.getState() == TransferTask.State.DONE).count();
        final var cancelled = (int) transfers.stream().filter(t -> t.getState() == TransferTask.State.CANCELLED).count();
        return Formats.plural(done, "transfer") + " done" + (failed > 0 ? ", " + failed + " failed" : "")
                + (cancelled > 0 ? ", " + cancelled + " cancelled" : "");
    }

    private Div transferRow(TransferTask task) {
        final var badge = Ui.span("fb-dock-glyph fb-dock-glyph--transfer");
        final var badgeIcon = task.getState() == TransferTask.State.DONE ? new Icon("check")
                : task.getState() == TransferTask.State.FAILED ? new Icon("warn")
                : new Icon(task.getKind() == TransferTask.Kind.MOVE ? "move" : "copy");
        badge.add(badgeIcon);
        final var row = Ui.div("fb-dock-row", badge, transferBody(task));
        if (!task.finished()) {
            if (task.getItems().size() > 1) {
                final var counter = Ui.span("fb-mono fb-dock-pct", task.getItemsDone() + " of " + task.getItems().size());
                row.add(counter);
            }
            final var cancel = Ui.iconButton("cross", "Cancel", () -> onCancel.accept(task));
            cancel.addClassName("fb-dock-cancel");
            row.add(cancel);
        }
        return row;
    }

    /** What is moved or copied where, and how far it got. */
    private static Div transferBody(TransferTask task) {
        final var verb = task.finished() ? "" : task.getKind().getRunning() + " ";
        final var where = task.betweenFileSystems() && !task.getDestination().isEmpty()
                ? task.destinationName() + " · " + task.getDestinationRoot().name() : task.destinationName();
        final var nameText = new Span(verb + task.label() + " → " + where);
        final var name = Ui.div("fb-dock-name", nameText);
        final var body = Ui.div("fb-dock-body", name);
        switch (task.getState()) {
            case DONE -> body.add(line("fb-dock-done", task.getMessage()));
            case FAILED -> body.add(line("fb-dock-failed", task.getMessage()));
            case CANCELLED -> body.add(line("fb-dock-note", task.getMessage()));
            case QUEUED -> body.add(line("fb-dock-note", "Queued"));
            case RUNNING -> {
                final var progress = task.progress();
                final var fill = Ui.div("fb-dock-fill");
                if (progress != null) {
                    fill.getStyle().setWidth(progress + "%");
                }
                final var bar = Ui.div("fb-dock-bar" + (progress == null ? " fb-dock-bar--busy" : ""), fill);
                body.add(bar);
                if (task.getStatus() != null) {
                    body.add(line("fb-dock-note", task.getStatus()));
                }
            }
        }
        return body;
    }

    private static Div line(String className, String text) {
        final var span = new Span(text);
        return Ui.div(className, span);
    }
}
