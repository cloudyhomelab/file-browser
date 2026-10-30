package org.vaadin.filebrowser.ui.dialog;

import java.time.Duration;
import java.util.List;

import org.vaadin.filebrowser.data.Formats;
import org.vaadin.filebrowser.storage.StorageEntry;
import org.vaadin.filebrowser.ui.Buttons;
import org.vaadin.filebrowser.ui.Icon;
import org.vaadin.filebrowser.ui.Ui;

import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.function.SerializableRunnable;

/** Confirms a delete; with a folder among the entries, only after typing its name or "Delete N items". */
public class DeleteDialog extends FbDialog {

    /** @param delay how long Undo is offered afterwards; null when the delete can't be undone */
    public DeleteDialog(List<StorageEntry> entries, Duration delay, SerializableRunnable onConfirm) {
        super(title(entries), "trash", Tone.DANGER, Width.NARROW);
        final var many = entries.size() > 1;
        final var label = many ? entries.size() + " items" : "“" + entries.getFirst().name() + "”";
        final var when = delay == null
                ? " will be deleted permanently. This can't be undone."
                : " will be deleted permanently in " + Formats.plural((int) delay.toSeconds(), "second")
                + ". You can undo it until then.";
        final var text = new Paragraph(label + when);
        text.setClassName("fb-dialog-text");
        add(text);

        final var first = entries.getFirst();
        final var childCount = first.childCount();
        if (!many && first.folder() && childCount != null && childCount > 0) {
            final var warning = contentsWarning(childCount);
            add(warning);
        }

        final var delete = Buttons.create(Buttons.Kind.DANGER_FILL, "Delete" + (many ? " " + entries.size() + " items" : ""), "trash", () -> {
            close();
            onConfirm.run();
        });
        if (typed(entries)) {
            if (many) {
                final var list = itemList(entries);
                add(list);
            }
            final var field = confirmField(entries, delete);
            add(field);
        }
        final var cancel = cancelButton();
        footer(cancel, delete);
    }

    /** Any folder asks for typing: not every storage type can say how much is inside. */
    static boolean typed(List<StorageEntry> entries) {
        return entries.stream().anyMatch(StorageEntry::folder);
    }

    /** What to type: the folder's name, or a phrase with the count, which a bare number would make too quick to type. */
    static String confirmation(List<StorageEntry> entries) {
        return entries.size() == 1 ? entries.getFirst().name() : "Delete " + entries.size() + " items";
    }

    /** A folder's name must match exactly; the phrase in any case. */
    static boolean confirms(List<StorageEntry> entries, String text) {
        final var expected = confirmation(entries).strip();
        final var typed = text.strip();
        return entries.size() == 1 ? expected.equals(typed) : expected.equalsIgnoreCase(typed);
    }

    private static Div contentsWarning(int childCount) {
        final var warnIcon = new Icon("warn");
        final var warnText = Ui.span("", "This folder contains " + Formats.plural(childCount, "item")
                + " that will also be deleted.");
        return Ui.div("fb-callout fb-callout--warning", warnIcon, warnText);
    }

    /** Keeps {@code delete} disabled until the field holds what {@link #confirmation} asks for. */
    private static TextField confirmField(List<StorageEntry> entries, Button delete) {
        final var field = new TextField("Type “" + confirmation(entries) + "” to confirm");
        field.setWidthFull();
        field.addClassNames("fb-field", "fb-delete-confirm");
        field.setAutofocus(true);
        field.setValueChangeMode(ValueChangeMode.EAGER);
        delete.setEnabled(false);
        field.addValueChangeListener(e -> delete.setEnabled(confirms(entries, e.getValue())));
        field.addKeyPressListener(Key.ENTER, e -> {
            if (confirms(entries, field.getValue())) {
                delete.click();
            }
        });
        return field;
    }

    private static Div itemList(List<StorageEntry> entries) {
        final var list = Ui.div("fb-delete-list");
        for (final var entry : entries) {
            final var icon = new Icon(entry.folder() ? "folder" : "doc");
            final var name = Ui.span("fb-ellipsis", entry.name());
            final var row = Ui.div("fb-delete-list-item", icon, name);
            list.add(row);
        }
        return list;
    }

    private static String title(List<StorageEntry> entries) {
        if (entries.size() > 1) {
            return "Delete " + entries.size() + " items?";
        }
        return entries.getFirst().folder() ? "Delete folder?" : "Delete file?";
    }
}
