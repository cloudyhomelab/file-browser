package org.vaadin.filebrowser.ui.dialog;

import java.util.function.Predicate;

import org.vaadin.filebrowser.ui.Buttons;

import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.function.SerializableConsumer;
import com.vaadin.flow.function.SerializablePredicate;

/** Single-field dialog used for "New folder" and "Rename". */
public final class NameDialog extends FbDialog {

    private final TextField field = new TextField();

    private NameDialog(String title, String icon, String label, String initial, String confirmText,
                       SerializablePredicate<String> valid, SerializableConsumer<String> onConfirm) {
        super(title, icon, Tone.ACCENT, Width.NARROW);
        field.setLabel(label);
        field.setValue(initial);
        field.setWidthFull();
        field.addClassName("fb-field");
        field.setAutofocus(true);
        field.setValueChangeMode(ValueChangeMode.EAGER);

        final var confirm = Buttons.create(Buttons.Kind.PRIMARY, confirmText, null, () -> { });
        final Predicate<String> ok = v -> !v.isBlank() && valid.test(v);
        final Runnable submit = () -> {
            if (ok.test(field.getValue())) {
                close();
                onConfirm.accept(field.getValue().trim());
            }
        };
        confirm.addClickListener(e -> submit.run());
        confirm.setEnabled(ok.test(initial));
        field.addValueChangeListener(e -> confirm.setEnabled(ok.test(e.getValue())));
        field.addKeyPressListener(Key.ENTER, e -> submit.run());
        add(field);
        final var cancel = cancelButton();
        footer(cancel, confirm);
        addOpenedChangeListener(e -> {
            if (e.isOpened()) {
                field.getElement().executeJs("setTimeout(() => { this.focus(); this.inputElement && this.inputElement.select(); })");
            }
        });
    }

    public static NameDialog newFolder(SerializableConsumer<String> onConfirm) {
        return new NameDialog("New folder", "folder", "Folder name", "Untitled folder", "Create", v -> true, onConfirm);
    }

    public static NameDialog rename(String current, boolean folder, SerializableConsumer<String> onConfirm) {
        return new NameDialog(folder ? "Rename folder" : "Rename file", "edit", "Name", current, "Rename",
                v -> !v.equals(current), onConfirm);
    }
}
