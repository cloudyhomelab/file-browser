package org.vaadin.filebrowser.ui.dialog;

import java.util.List;

import org.vaadin.filebrowser.data.Formats;
import org.vaadin.filebrowser.storage.StorageEntry;
import org.vaadin.filebrowser.ui.Buttons;
import org.vaadin.filebrowser.ui.Icon;
import org.vaadin.filebrowser.ui.Ui;

import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.function.SerializableRunnable;

public class DeleteDialog extends FbDialog {

    public DeleteDialog(List<StorageEntry> entries, SerializableRunnable onConfirm) {
        super(title(entries), "trash", Tone.DANGER, Width.NARROW);
        final var many = entries.size() > 1;
        final var label = many ? entries.size() + " items" : "“" + entries.getFirst().name() + "”";
        final var text = new Paragraph(label + " will be deleted permanently. This can't be undone.");
        text.setClassName("fb-dialog-text");
        add(text);

        final var first = entries.getFirst();
        final var childCount = first.childCount();
        if (!many && first.folder() && childCount != null && childCount > 0) {
            final var warnIcon = new Icon("warn");
            final var warnText = Ui.span("", "This folder contains " + Formats.plural(childCount, "item")
                    + " that will also be deleted.");
            final var callout = Ui.div("fb-callout fb-callout--warning", warnIcon, warnText);
            add(callout);
        }

        final var delete = Buttons.create(Buttons.Kind.DANGER_FILL, "Delete" + (many ? " " + entries.size() : ""), "trash", () -> {
            close();
            onConfirm.run();
        });
        final var cancel = cancelButton();
        footer(cancel, delete);
    }

    private static String title(List<StorageEntry> entries) {
        if (entries.size() > 1) {
            return "Delete " + entries.size() + " items?";
        }
        return entries.getFirst().folder() ? "Delete folder?" : "Delete file?";
    }
}
