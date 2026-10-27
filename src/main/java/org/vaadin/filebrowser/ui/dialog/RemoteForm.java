package org.vaadin.filebrowser.ui.dialog;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.vaadin.filebrowser.ui.Icon;
import org.vaadin.filebrowser.ui.Ui;

import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.Autocomplete;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.textfield.TextFieldBase;

/**
 * A folder on a remote server. Sign-in details go straight to the server and are never shown
 * again. When the server's key or certificate isn't trusted yet, Connect shows its fingerprint
 * and the admin must confirm it; the confirmed fingerprint is stored and checked from then on.
 * Once added, only the name and access can change.
 */
abstract class RemoteForm extends RootForm {

    protected final TextField user = new TextField("User name");
    protected final PasswordField password = new PasswordField("Password");
    protected final Div credentials = Ui.div("fb-addroot-creds");
    private final Div credentialsHint = Ui.div("fb-addroot-creds-hint");
    private final Div trust = Ui.div("fb-addroot-creds fb-addroot-trust");
    private final String pinKey;
    private final String identity;
    /** The fingerprint the server showed on the last Connect, and the endpoint it was shown for. */
    private String offered;
    private String offeredFor;
    private boolean trusted;
    /** The secrets the file system being edited has; empty when adding. */
    private final Set<String> stored;

    /**
     * @param pinKey   the setting the confirmed fingerprint is stored under
     * @param identity what the fingerprint is of, e.g. "host key"
     */
    protected RemoteForm(Context context, String pinKey, String identity) {
        super(context);
        this.pinKey = pinKey;
        this.identity = identity;
        user.addClassName("fb-field--mono");
        user.setAutocomplete(Autocomplete.OFF);
        password.addClassName("fb-field--mono");
        password.setAutocomplete(Autocomplete.NEW_PASSWORD);
        field(user);
        field(password);
        final var lockIcon = new Icon("lock");
        final var credsLabel = new Span("Sign-in");
        final var credsTitle = Ui.div("fb-addroot-creds-title", lockIcon, credsLabel);
        credentials.add(credsTitle);
        trust.setVisible(false);
        stored = editing() ? context.workspace().secretNames(context.editing()) : Set.of();
        if (editing()) {
            final var pinned = context.editing().settings().get(pinKey);
            if (pinned != null) {
                showPinned(pinned);
            }
        }
    }

    /**
     * When editing, shows {@code field} as stored but never its value: secrets can't be shown again.
     *
     * @param key the secret it holds
     */
    protected void lockSecret(TextFieldBase<?, String> field, String key) {
        field.setReadOnly(true);
        field.setPlaceholder(stored.contains(key) ? "Stored encrypted" : "Not set");
    }

    /** True when the file system being edited has the secret {@code key}. */
    protected boolean hasSecret(String key) {
        return stored.contains(key);
    }

    private void showPinned(String fingerprint) {
        final var title = Ui.div("fb-addroot-creds-title", new Icon("lock"), new Span("Trusted " + identity));
        final var shown = Ui.div("fb-addroot-fingerprint", new Span(fingerprint));
        final var hint = Ui.div("fb-addroot-creds-hint", new Span("The file system stops connecting if the "
                + "server shows a different one."));
        trust.add(title, shown, hint);
        trust.addClassName("fb-addroot-trust--pinned");
        trust.setVisible(true);
    }

    /** Adds the sign-in box and the fingerprint box after whatever the subclass added. */
    protected void addCredentials() {
        credentials.add(credentialsHint);
        add(credentials, trust);
    }

    @Override
    public void offerFingerprint(String fingerprint) {
        offered = fingerprint;
        offeredFor = endpoint();
        trusted = false;
        trust.removeAll();
        final var title = Ui.div("fb-addroot-creds-title", new Icon("warn"), new Span("Confirm the server's " + identity));
        final var shown = Ui.div("fb-addroot-fingerprint", new Span(fingerprint));
        final var hint = Ui.div("fb-addroot-creds-hint", new Span("Compare it with the fingerprint the server's "
                + "administrator gives you. It is stored, and the file system stops connecting if it ever changes."));
        final var confirm = new Checkbox("I trust this " + identity);
        confirm.addValueChangeListener(e -> {
            trusted = e.getValue();
            context.onChange().run();
        });
        trust.add(title, shown, hint, confirm);
        trust.setVisible(true);
        context.onChange().run();
    }

    /** Forgets a fingerprint shown for other settings, e.g. another host or a different security mode. */
    protected void forgetFingerprint() {
        offered = null;
        offeredFor = null;
        trusted = false;
        trust.removeAll();
        trust.setVisible(false);
    }

    @Override
    protected final void updateFields() {
        if (offered != null && !endpoint().equals(offeredFor)) {
            forgetFingerprint();
        }
        updateRemoteFields();
        // nothing typed yet is not an error; Connect just stays disabled
        final var problem = credentialsStarted() && !editing() ? credentialsProblem() : null;
        credentialsHint.removeAll();
        credentialsHint.getElement().getClassList().set("fb-field-error-text", problem != null);
        credentialsHint.add(problem != null ? problem
                : editing() ? "Never shown again. To change it, remove the file system and add it again."
                : credentialsNote());
    }

    /** Updates the type's own fields. */
    protected void updateRemoteFields() {
    }

    /** What is missing from the sign-in details, or null when they are complete. */
    protected abstract String credentialsProblem();

    /** Shown under the sign-in fields while nothing is missing. */
    protected String credentialsNote() {
        return "Stored encrypted and never shown again.";
    }

    /** True once anything has been entered for signing in. */
    protected boolean credentialsStarted() {
        return !blank(user) || !blank(password);
    }

    @Override
    protected final boolean fieldsValid() {
        return credentialsProblem() == null && (offered == null || trusted);
    }

    /** The type's own settings, besides a confirmed fingerprint. */
    protected abstract Map<String, String> remoteSettings();

    @Override
    public final Map<String, String> settings() {
        final var settings = new LinkedHashMap<>(remoteSettings());
        if (offered != null && trusted) {
            settings.put(pinKey, offered);
        }
        return settings;
    }

    protected static boolean blank(TextField field) {
        return field.getValue().trim().isEmpty();
    }

    protected static boolean blank(PasswordField field) {
        return field.getValue().isEmpty();
    }
}
