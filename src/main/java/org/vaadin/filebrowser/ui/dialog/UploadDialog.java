package org.vaadin.filebrowser.ui.dialog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.Formats;
import org.vaadin.filebrowser.data.IncomingFile;
import org.vaadin.filebrowser.service.LinkFetcher;
import org.vaadin.filebrowser.storage.StorageException;
import org.vaadin.filebrowser.ui.Buttons;
import org.vaadin.filebrowser.ui.FileDropArea;
import org.vaadin.filebrowser.ui.FileGlyph;
import org.vaadin.filebrowser.ui.Icon;
import org.vaadin.filebrowser.ui.Ui;

import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.function.SerializableConsumer;
import com.vaadin.flow.function.SerializableFunction;

/**
 * Pick files to upload by dropping them, browsing, or giving web links the server downloads; every
 * picked file starts selected.
 */
public class UploadDialog extends FbDialog {

    private final Catalog catalog;
    private final Map<String, IncomingFile> picked = new LinkedHashMap<>();
    private final List<IncomingFile> offered;
    private final Div list = Ui.div("fb-upload-list");
    private final Div sectionLabel;
    private final Button confirm;
    private final TextField link = new TextField("Or add from a link");
    private final long maxBytes;

    /** @param inspectLink reads a link's name and size on the server; throws with a user-facing message if refused */
    public UploadDialog(Catalog catalog, long maxBytes, SerializableFunction<String, LinkFetcher.LinkInfo> inspectLink,
                        SerializableConsumer<List<IncomingFile>> onConfirm) {
        super("Upload files", "upload", Tone.ACCENT, Width.MEDIUM);
        this.catalog = catalog;
        this.offered = new ArrayList<>();
        this.maxBytes = maxBytes;
        final var sizeLimit = Formats.size(maxBytes);

        final var zone = new FileDropArea(catalog, true);
        zone.addClassName("fb-upload-zone");
        zone.getElement().setAttribute("role", "button");
        zone.getElement().setAttribute("tabindex", "0");
        final var uploadIcon = new Icon("upload");
        final var zoneIcon = Ui.span("fb-upload-zone-icon", uploadIcon);
        final var zoneTitle = Ui.span("fb-upload-zone-title", "Drag files here, or click to browse");
        final var zoneSub = Ui.span("fb-upload-zone-sub", "Up to " + sizeLimit + " per file");
        zone.add(zoneIcon, zoneTitle, zoneSub);
        zone.onFiles(files -> {
            files.forEach(this::offer);
            render();
        });

        link.addClassNames("fb-field", "fb-field--mono", "fb-upload-link");
        link.setWidthFull();
        link.setPlaceholder("https://example.com/report.pdf");
        link.setClearButtonVisible(true);
        link.setValueChangeMode(ValueChangeMode.EAGER);
        link.addValueChangeListener(e -> link.setInvalid(false));
        final var add = Buttons.create(Buttons.Kind.SECONDARY, "Add", "link", () -> addLink(inspectLink));
        add.addClassName("fb-field-browse");
        link.setSuffixComponent(add);
        link.addKeyPressListener(Key.ENTER, e -> addLink(inspectLink));

        final var sectionText = Ui.span("", "Selected files");
        sectionLabel = Ui.div("fb-section-label", sectionText);
        add(zone, link, sectionLabel, list);
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

    private void offer(IncomingFile f) {
        offered.removeIf(o -> o.name().equals(f.name()));
        offered.addFirst(f);
        picked.put(f.name(), f);
    }

    private void addLink(SerializableFunction<String, LinkFetcher.LinkInfo> inspectLink) {
        final var url = link.getValue().trim();
        if (url.isEmpty()) {
            return;
        }
        final LinkFetcher.LinkInfo info;
        try {
            info = inspectLink.apply(url);
        } catch (StorageException e) {
            link.setErrorMessage(e.getMessage());
            link.setInvalid(true);
            return;
        }
        if (info.size() != null && info.size() > maxBytes) {
            link.setErrorMessage("The file at this link is larger than the upload limit (" + Formats.size(maxBytes) + ").");
            link.setInvalid(true);
            return;
        }
        offer(IncomingFile.link(info.url(), info.name(), catalog.typeForFileName(info.name()), info.size()));
        link.clear();
        render();
    }

    private void render() {
        sectionLabel.setVisible(!offered.isEmpty());
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
            if (f.fromLink()) {
                final var linkIcon = new Icon("link");
                final var fromLink = Ui.span("fb-upload-item-link", linkIcon);
                fromLink.getElement().setAttribute("title", f.url());
                name.addComponentAsFirst(fromLink);
            }
            final var sizeText = Formats.size(f.knownSize());
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
