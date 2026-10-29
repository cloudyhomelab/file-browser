package org.vaadin.filebrowser.ui.dialog;

import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.RootOptions;
import org.vaadin.filebrowser.ui.Buttons;
import org.vaadin.filebrowser.ui.Switch;
import org.vaadin.filebrowser.ui.Ui;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.function.SerializableConsumer;

/** What admins allow on one file system, besides its access. Saved changes apply at once, for everyone. */
public class RootSettingsDialog extends FbDialog {

    private final Switch linkUploads;
    private final Switch extraction;
    private final Switch opening;

    public RootSettingsDialog(FsRoot root, SerializableConsumer<RootOptions> onSave) {
        super("Settings for “" + root.name() + "”", "cog", Tone.ACCENT, Width.MEDIUM);
        final var options = root.options();
        linkUploads = new Switch("Allow uploads from web links", options.linkUploads(), on -> { });
        extraction = new Switch("Allow archive extraction", options.extraction(), on -> { });
        opening = new Switch("Allow opening files", options.opening(), on -> { });
        final var label = Ui.div("fb-form-label", Ui.span("", "Features"));
        final var features = Ui.div("fb-form-section fb-settings-features", label,
                option(linkUploads, "The server downloads files from links people paste into the upload dialog."),
                option(extraction, "Archives can be extracted, and compressed files decompressed, as they are "
                        + "uploaded."),
                option(opening, "Files can be viewed in the page. Downloading is always allowed."));
        add(features);
        final var save = Buttons.create(Buttons.Kind.PRIMARY, "Save", null, () -> {
            close();
            onSave.accept(new RootOptions(linkUploads.isOn(), extraction.isOn(), opening.isOn()));
        });
        footer(cancelButton(), save);
    }

    private static Div option(Switch toggle, String hint) {
        final var text = Ui.div("fb-settings-hint", Ui.span("", hint));
        return Ui.div("fb-settings-option", toggle, text);
    }
}
