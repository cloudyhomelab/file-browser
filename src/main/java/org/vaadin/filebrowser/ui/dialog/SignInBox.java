package org.vaadin.filebrowser.ui.dialog;

import java.util.Set;
import java.util.function.Consumer;

import org.vaadin.filebrowser.ui.Icon;
import org.vaadin.filebrowser.ui.Ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.Autocomplete;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.textfield.TextFieldBase;

import lombok.AccessLevel;
import lombok.Getter;

/**
 * The sign-in details of a file system on a remote server, all stored as secrets. They go straight
 * to the server and are never shown again: when editing, each field only says whether it is set.
 */
class SignInBox extends Div {

    /** The usual note under the fields while nothing is missing. */
    static final String STORED = "Stored encrypted and never shown again.";

    @Getter(AccessLevel.PACKAGE)
    private final TextField user = new TextField("User name");
    @Getter(AccessLevel.PACKAGE)
    private final PasswordField password = new PasswordField("Password");
    private final Div hint = Ui.div("fb-addroot-creds-hint");
    private final boolean editing;
    /** The secrets the file system being edited has; empty when adding. */
    private final Set<String> stored;

    /** @param setUp sets up a field like every other in the form */
    SignInBox(RootForm.Context context, Consumer<TextFieldBase<?, String>> setUp) {
        addClassName("fb-addroot-creds");
        editing = context.editing() != null;
        stored = editing ? context.workspace().secretNames(context.editing()) : Set.of();
        user.addClassName("fb-field--mono");
        user.setAutocomplete(Autocomplete.OFF);
        password.addClassName("fb-field--mono");
        password.setAutocomplete(Autocomplete.NEW_PASSWORD);
        setUp.accept(user);
        setUp.accept(password);
        final var lockIcon = new Icon("lock");
        final var label = new Span("Sign-in");
        final var title = Ui.div("fb-addroot-creds-title", lockIcon, label);
        add(title);
    }

    /** Adds the fields, in order, above the hint. */
    void fields(Component... fields) {
        add(fields);
        add(hint);
    }

    /**
     * When editing, shows {@code field} as stored but never its value.
     *
     * @param key the secret it holds
     */
    void lock(TextFieldBase<?, String> field, String key) {
        field.setReadOnly(true);
        field.setPlaceholder(stored.contains(key) ? "Stored encrypted" : "Not set");
    }

    /** True when the file system being edited has the secret {@code key}. */
    boolean has(String key) {
        return stored.contains(key);
    }

    /** True once a user name or password has been entered. */
    boolean started() {
        return !blank(user) || !blank(password);
    }

    /**
     * Shows what is missing, or {@code note} when nothing is.
     *
     * @param started whether anything has been entered; nothing typed yet is not an error
     * @param problem what is missing, or null
     */
    void update(boolean started, String problem, String note) {
        final var shown = started && !editing ? problem : null;
        hint.removeAll();
        hint.getElement().getClassList().set("fb-field-error-text", shown != null);
        hint.add(shown != null ? shown
                : editing ? "Never shown again. To change it, remove the file system and add it again."
                : note);
    }

    static boolean blank(TextField field) {
        return field.getValue().isBlank();
    }

    static boolean blank(PasswordField field) {
        return field.getValue().isEmpty();
    }
}
