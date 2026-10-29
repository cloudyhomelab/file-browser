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
        final var activeTransfers = (int) transfers.stream().filter(t -> !t.finished()).count();
        final var active = (int) uploads.stream().filter(u -> !u.finished()).count() + activeTransfers;
        final var title = title(uploads, transfers);

        final var caret = new Icon("angle-down");
        caret.addClassName("fb-dock-caret");
        final var titleLabel = Ui.span("fb-dock-title", title);
        final var dismiss = Ui.iconButton("cross", active > 0 ? "Cancel all" : "Dismiss", onDismiss);
        final var head = Ui.div("fb-dock-head", titleLabel, caret, dismiss);
        head.addClickListener(e -> {
            collapsed = !collapsed;
            show(uploads, transfers);
        });
        div.getElement().getClassList().set("fb-dock--collapsed", collapsed);
        div.add(head);
        if (collapsed) {
            return;
        }

        final var list = Ui.div("fb-dock-list");
        for (final var u : uploads) {
            final var info = catalog.fileType(u.getType());
            final var badge = Ui.span("fb-dock-glyph fb-type-" + u.getType());
            final var failedRow = u.getState() == UploadTask.State.FAILED;
            if (u.complete()) {
                final var checkIcon = new Icon("check");
                badge.add(checkIcon);
            } else if (failedRow) {
                final var warnIcon = new Icon("warn");
                badge.add(warnIcon);
            } else {
                final var label = info.label().isEmpty() ? "•" : info.label();
                final var shortLabel = label.substring(0, Math.min(GLYPH_LABEL_CHARS, label.length()));
                badge.add(shortLabel);
            }
            final var nameText = new Span(u.getName());
            final var name = Ui.div("fb-dock-name", nameText);
            final var body = Ui.div("fb-dock-body", name);
            if (u.complete()) {
                final var sizeText = u.getSummary() != null ? u.getSummary() : Formats.size(u.knownSize()) + " · Done";
                final var doneText = new Span(sizeText);
                final var doneLine = Ui.div("fb-dock-done", doneText);
                body.add(doneLine);
            } else if (failedRow) {
                final var errorText = new Span(u.getError());
                final var errorLine = Ui.div("fb-dock-failed", errorText);
                body.add(errorLine);
            } else if (u.getState() == UploadTask.State.EXTRACTING) {
                final var extractingText = new Span("Extracting…");
                final var extractingLine = Ui.div("fb-dock-done", extractingText);
                body.add(extractingLine);
            } else {
                final var fill = Ui.div("fb-dock-fill");
                fill.getStyle().setWidth(u.progress() + "%");
                final var bar = Ui.div("fb-dock-bar", fill);
                body.add(bar);
            }
            final var row = Ui.div("fb-dock-row", badge, body);
            if (!u.finished()) {
                final var percent = Ui.span("fb-mono fb-dock-pct", u.progress() + "%");
                row.add(percent);
            }
            list.add(row);
        }
        transfers.forEach(t -> list.add(transferRow(t)));
        div.add(list);
    }

    private static String title(List<UploadTask> uploads, List<TransferTask> transfers) {
        final var activeUploads = (int) uploads.stream().filter(u -> !u.finished()).count();
        final var activeTransfers = (int) transfers.stream().filter(t -> !t.finished()).count();
        if (activeUploads > 0 || activeTransfers > 0) {
            final var uploading = activeUploads > 0 ? "Uploading " + Formats.plural(activeUploads, "item") : null;
            final var transferring = activeTransfers > 0 ? Formats.plural(activeTransfers, "transfer") + " running" : null;
            return (uploading == null ? transferring : transferring == null ? uploading
                    : uploading + ", " + transferring) + "…";
        }
        final var parts = new ArrayList<String>();
        if (!uploads.isEmpty()) {
            final var failed = (int) uploads.stream().filter(u -> u.getState() == UploadTask.State.FAILED).count();
            final var done = (int) uploads.stream().filter(UploadTask::complete).count();
            parts.add(Formats.plural(done, "upload") + " complete" + (failed > 0 ? ", " + failed + " failed" : ""));
        }
        if (!transfers.isEmpty()) {
            final var failed = (int) transfers.stream().filter(t -> t.getState() == TransferTask.State.FAILED).count();
            final var done = (int) transfers.stream().filter(t -> t.getState() == TransferTask.State.DONE).count();
            final var cancelled = (int) transfers.stream().filter(t -> t.getState() == TransferTask.State.CANCELLED).count();
            parts.add(Formats.plural(done, "transfer") + " done" + (failed > 0 ? ", " + failed + " failed" : "")
                    + (cancelled > 0 ? ", " + cancelled + " cancelled" : ""));
        }
        return String.join(" · ", parts);
    }

    private Div transferRow(TransferTask t) {
        final var badge = Ui.span("fb-dock-glyph fb-dock-glyph--transfer");
        final var badgeIcon = t.getState() == TransferTask.State.DONE ? new Icon("check")
                : t.getState() == TransferTask.State.FAILED ? new Icon("warn")
                : new Icon(t.getKind() == TransferTask.Kind.MOVE ? "move" : "copy");
        badge.add(badgeIcon);
        final var verb = t.finished() ? "" : t.getKind().getRunning() + " ";
        final var where = t.betweenFileSystems() && !t.getDestination().isEmpty()
                ? t.destinationName() + " · " + t.getDestinationRoot().name() : t.destinationName();
        final var nameText = new Span(verb + t.label() + " → " + where);
        final var name = Ui.div("fb-dock-name", nameText);
        final var body = Ui.div("fb-dock-body", name);
        switch (t.getState()) {
            case DONE -> body.add(line("fb-dock-done", t.getMessage()));
            case FAILED -> body.add(line("fb-dock-failed", t.getMessage()));
            case CANCELLED -> body.add(line("fb-dock-note", t.getMessage()));
            case QUEUED -> body.add(line("fb-dock-note", "Queued"));
            case RUNNING -> {
                final var progress = t.progress();
                final var fill = Ui.div("fb-dock-fill");
                if (progress != null) {
                    fill.getStyle().setWidth(progress + "%");
                }
                final var bar = Ui.div("fb-dock-bar" + (progress == null ? " fb-dock-bar--busy" : ""), fill);
                body.add(bar);
                if (t.getStatus() != null) {
                    body.add(line("fb-dock-note", t.getStatus()));
                }
            }
        }
        final var row = Ui.div("fb-dock-row", badge, body);
        if (!t.finished()) {
            if (t.getItems().size() > 1) {
                final var counter = Ui.span("fb-mono fb-dock-pct", t.getItemsDone() + " of " + t.getItems().size());
                row.add(counter);
            }
            final var cancel = Ui.iconButton("cross", "Cancel", () -> onCancel.accept(t));
            cancel.addClassName("fb-dock-cancel");
            row.add(cancel);
        }
        return row;
    }

    private static Div line(String className, String text) {
        final var span = new Span(text);
        return Ui.div(className, span);
    }
}
