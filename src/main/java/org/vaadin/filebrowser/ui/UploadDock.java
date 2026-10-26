package org.vaadin.filebrowser.ui;

import java.util.List;

import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.Formats;
import org.vaadin.filebrowser.service.UploadTask;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.function.SerializableRunnable;

/** Bottom-right panel listing simulated uploads with their progress. */
public class UploadDock extends Composite<Div> {

    private static final int GLYPH_LABEL_CHARS = 3;

    private final Catalog catalog;
    private final SerializableRunnable onDismiss;
    private boolean collapsed;

    public UploadDock(Catalog catalog, SerializableRunnable onDismiss) {
        this.catalog = catalog;
        this.onDismiss = onDismiss;
        getContent().setClassName("fb-dock");
        getContent().setVisible(false);
    }

    public void show(List<UploadTask> uploads) {
        final var div = getContent();
        div.removeAll();
        div.setVisible(!uploads.isEmpty());
        if (uploads.isEmpty()) {
            return;
        }
        final var active = (int) uploads.stream().filter(u -> !u.complete()).count();
        final var done = uploads.size() - active;
        final var title = active > 0 ? "Uploading " + Formats.plural(active, "item") + "…"
                : Formats.plural(done, "upload") + " complete";

        final var caret = new Icon("angle-down");
        caret.addClassName("fb-dock-caret");
        final var titleLabel = Ui.span("fb-dock-title", title);
        final var dismiss = Ui.iconButton("cross", "Dismiss", onDismiss);
        final var head = Ui.div("fb-dock-head", titleLabel, caret, dismiss);
        head.addClickListener(e -> {
            collapsed = !collapsed;
            show(uploads);
        });
        div.getElement().getClassList().set("fb-dock--collapsed", collapsed);
        div.add(head);
        if (collapsed) {
            return;
        }

        final var list = Ui.div("fb-dock-list");
        for (final var u : uploads) {
            final var info = catalog.fileType(u.type());
            final var badge = Ui.span("fb-dock-glyph fb-type-" + u.type());
            if (u.complete()) {
                final var checkIcon = new Icon("check");
                badge.add(checkIcon);
            } else {
                final var label = info.label().isEmpty() ? "•" : info.label();
                final var shortLabel = label.substring(0, Math.min(GLYPH_LABEL_CHARS, label.length()));
                badge.add(shortLabel);
            }
            final var nameText = new Span(u.name());
            final var name = Ui.div("fb-dock-name", nameText);
            final var body = Ui.div("fb-dock-body", name);
            if (u.complete()) {
                final var sizeText = Formats.size(u.size());
                final var doneText = new Span(sizeText + " · Done");
                final var doneLine = Ui.div("fb-dock-done", doneText);
                body.add(doneLine);
            } else {
                final var fill = Ui.div("fb-dock-fill");
                fill.getStyle().setWidth(u.progress() + "%");
                final var bar = Ui.div("fb-dock-bar", fill);
                body.add(bar);
            }
            final var row = Ui.div("fb-dock-row", badge, body);
            if (!u.complete()) {
                final var percent = Ui.span("fb-mono fb-dock-pct", u.progress() + "%");
                row.add(percent);
            }
            list.add(row);
        }
        div.add(list);
    }
}
