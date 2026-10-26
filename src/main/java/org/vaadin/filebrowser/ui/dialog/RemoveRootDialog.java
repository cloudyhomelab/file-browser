package org.vaadin.filebrowser.ui.dialog;

import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.ui.Buttons;

import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.function.SerializableRunnable;

/** Confirms unregistering a file system. Nothing on the storage itself is touched. */
public class RemoveRootDialog extends FbDialog {

    public RemoveRootDialog(FsRoot root, SerializableRunnable onConfirm) {
        super("Remove file system?", "trash", Tone.DANGER, Width.NARROW);
        final var text = new Paragraph("“" + root.name() + "” will no longer be listed for anyone. The files stay on "
                + "the disk; everyone's stars in it are removed.");
        text.setClassName("fb-dialog-text");
        add(text);
        final var remove = Buttons.create(Buttons.Kind.DANGER_FILL, "Remove", "trash", () -> {
            close();
            onConfirm.run();
        });
        final var cancel = cancelButton();
        footer(cancel, remove);
    }
}
