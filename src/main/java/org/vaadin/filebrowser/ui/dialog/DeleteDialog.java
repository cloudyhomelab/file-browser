package org.vaadin.filebrowser.ui.dialog;

import java.util.List;

import org.vaadin.filebrowser.data.FsEntry;
import org.vaadin.filebrowser.ui.Buttons;
import org.vaadin.filebrowser.ui.Icon;
import org.vaadin.filebrowser.ui.Ui;

import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.function.SerializableRunnable;

public class DeleteDialog extends FbDialog {

    public DeleteDialog(List<FsEntry> entries, SerializableRunnable onConfirm) {
        super(title(entries), "trash", Tone.DANGER, Width.NARROW);
        final var many = entries.size() > 1;
        final var label = many ? entries.size() + " items" : "“" + entries.getFirst().name() + "”";
        final var text = new Paragraph(label + " will be moved to trash. Items in trash are permanently removed after 30 days.");
        text.setClassName("fb-dialog-text");
        add(text);

        final var first = entries.getFirst();
        if (!many && first.folder() && !first.children().isEmpty()) {
            final var warnIcon = new Icon("warn");
            final var warnText = Ui.span("", "This folder contains " + first.children().size() + " items that will also be deleted.");
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

    private static String title(List<FsEntry> entries) {
        if (entries.size() > 1) {
            return "Delete " + entries.size() + " items?";
        }
        return entries.getFirst().folder() ? "Delete folder?" : "Delete file?";
    }
}
