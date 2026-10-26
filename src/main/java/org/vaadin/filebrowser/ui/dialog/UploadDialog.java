package org.vaadin.filebrowser.ui.dialog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.Formats;
import org.vaadin.filebrowser.data.IncomingFile;
import org.vaadin.filebrowser.ui.Buttons;
import org.vaadin.filebrowser.ui.FileDropArea;
import org.vaadin.filebrowser.ui.FileGlyph;
import org.vaadin.filebrowser.ui.Icon;
import org.vaadin.filebrowser.ui.Ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.function.SerializableConsumer;

/** Pick files to upload: drop or browse real files, or choose from the demo sample set. */
public class UploadDialog extends FbDialog {

    private final Catalog catalog;
    private final Map<String, IncomingFile> picked = new LinkedHashMap<>();
    private final List<IncomingFile> offered;
    private final Div list = Ui.div("fb-upload-list");
    private final Button confirm;

    public UploadDialog(Catalog catalog, SerializableConsumer<List<IncomingFile>> onConfirm) {
        super("Upload files", "upload", Tone.ACCENT, Width.MEDIUM);
        this.catalog = catalog;
        this.offered = new ArrayList<>(catalog.sampleFiles());

        final var zone = new FileDropArea(catalog, true);
        zone.addClassName("fb-upload-zone");
        zone.getElement().setAttribute("role", "button");
        zone.getElement().setAttribute("tabindex", "0");
        final var uploadIcon = new Icon("upload");
        final var zoneIcon = Ui.span("fb-upload-zone-icon", uploadIcon);
        final var zoneTitle = Ui.span("fb-upload-zone-title", "Drag files here, click to browse, or pick from the sample set below");
        final var zoneSub = Ui.span("fb-upload-zone-sub", "Up to 5 GB per file");
        zone.add(zoneIcon, zoneTitle, zoneSub);
        zone.onFiles(files -> {
            for (final var f : files) {
                offered.removeIf(o -> o.name().equals(f.name()));
                offered.addFirst(f);
                picked.put(f.name(), f);
            }
            render();
        });

        final var sectionText = Ui.span("", "Sample files");
        final var sectionLabel = Ui.div("fb-section-label", sectionText);
        add(zone, sectionLabel, list);
        confirm = Buttons.create(Buttons.Kind.PRIMARY, "Upload", "upload", () -> {
            if (!picked.isEmpty()) {
                close();
                final var files = List.copyOf(picked.values());
                onConfirm.accept(files);
            }
        });
        final var cancel = cancelButton();
        footer(cancel, confirm);
        render();
    }

    private void render() {
        list.removeAll();
        for (final var f : offered) {
            final var selected = picked.containsKey(f.name());
            final var box = Ui.span("fb-check fb-check--l" + (selected ? " fb-check--on" : ""));
            if (selected) {
                final var checkIcon = new Icon("check");
                box.add(checkIcon);
            }
            final var glyph = new FileGlyph(f.type(), catalog);
            final var name = Ui.span("fb-upload-item-name", f.name());
            final var sizeText = Formats.size(f.size());
            final var size = Ui.span("fb-mono fb-muted", sizeText);
            final var row = Ui.div("fb-upload-item" + (selected ? " fb-upload-item--on" : ""), glyph, name, size, box);
            row.getElement().setAttribute("role", "checkbox");
            final var ariaChecked = String.valueOf(selected);
            row.getElement().setAttribute("aria-checked", ariaChecked);
            row.addClickListener(e -> {
                if (picked.remove(f.name()) == null) {
                    picked.put(f.name(), f);
                }
                render();
            });
            list.add(row);
        }
        confirm.setText(picked.isEmpty() ? "Upload" : "Upload " + picked.size());
        confirm.setEnabled(!picked.isEmpty());
    }
}
