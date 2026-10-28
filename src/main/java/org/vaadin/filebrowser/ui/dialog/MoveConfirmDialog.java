package org.vaadin.filebrowser.ui.dialog;

import java.util.List;

import org.vaadin.filebrowser.storage.StorageEntry;
import org.vaadin.filebrowser.ui.Buttons;

import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.function.SerializableRunnable;

/** Asks before moving items that were dropped onto a folder. */
public class MoveConfirmDialog extends FbDialog {

    public MoveConfirmDialog(List<StorageEntry> entries, String destinationName, SerializableRunnable onConfirm) {
        super(title(entries), "move", Tone.ACCENT, Width.NARROW);
        final var label = entries.size() > 1 ? entries.size() + " items" : "“" + entries.getFirst().name() + "”";
        final var text = new Paragraph(label + " will be moved to “" + destinationName + "”.");
        text.setClassName("fb-dialog-text");
        add(text);

        final var move = Buttons.create(Buttons.Kind.PRIMARY, "Move", "move", () -> {
            close();
            onConfirm.run();
        });
        move.setAutofocus(true);
        final var cancel = cancelButton();
        footer(cancel, move);
    }

    private static String title(List<StorageEntry> entries) {
        return entries.size() > 1 ? "Move " + entries.size() + " items?" : "Move “" + entries.getFirst().name() + "”?";
    }
}
