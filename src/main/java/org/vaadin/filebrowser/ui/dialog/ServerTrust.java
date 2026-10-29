package org.vaadin.filebrowser.ui.dialog;

import java.util.Map;

import org.vaadin.filebrowser.ui.Icon;
import org.vaadin.filebrowser.ui.Ui;

import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;

/**
 * The server's key or certificate. When it isn't trusted yet, Connect shows its fingerprint and the
 * admin must confirm it; the confirmed fingerprint is stored and checked from then on.
 */
class ServerTrust extends Div {

    private final RootForm.Context context;
    private final String pinKey;
    private final String identity;
    /** The fingerprint the server showed on the last Connect, and the endpoint it was shown for. */
    private String offered;
    private String offeredFor;
    private boolean trusted;

    /**
     * @param pinKey   the setting the confirmed fingerprint is stored under
     * @param identity what the fingerprint is of, e.g. "host key"
     */
    ServerTrust(RootForm.Context context, String pinKey, String identity) {
        this.context = context;
        this.pinKey = pinKey;
        this.identity = identity;
        addClassNames("fb-addroot-creds", "fb-addroot-trust");
        setVisible(false);
        final var pinned = context.editing() == null ? null : context.editing().settings().get(pinKey);
        if (pinned != null) {
            showPinned(pinned);
        }
    }

    private void showPinned(String fingerprint) {
        final var title = Ui.div("fb-addroot-creds-title", new Icon("lock"), new Span("Trusted " + identity));
        final var shown = Ui.div("fb-addroot-fingerprint", new Span(fingerprint));
        final var hint = Ui.div("fb-addroot-creds-hint", new Span("The file system stops connecting if the "
                + "server shows a different one."));
        add(title, shown, hint);
        addClassName("fb-addroot-trust--pinned");
        setVisible(true);
    }

    /** Asks the admin to confirm {@code fingerprint}, shown by the server at {@code endpoint}. */
    void offer(String fingerprint, String endpoint) {
        offered = fingerprint;
        offeredFor = endpoint;
        trusted = false;
        removeAll();
        final var title = Ui.div("fb-addroot-creds-title", new Icon("warn"), new Span("Confirm the server's " + identity));
        final var shown = Ui.div("fb-addroot-fingerprint", new Span(fingerprint));
        final var hint = Ui.div("fb-addroot-creds-hint", new Span("Compare it with the fingerprint the server's "
                + "administrator gives you. It is stored, and the file system stops connecting if it ever changes."));
        final var confirm = new Checkbox("I trust this " + identity);
        confirm.addValueChangeListener(e -> {
            trusted = e.getValue();
            context.onChange().run();
        });
        add(title, shown, hint, confirm);
        setVisible(true);
        context.onChange().run();
    }

    /** Forgets a fingerprint shown for other settings, e.g. a different security mode. */
    void forget() {
        offered = null;
        offeredFor = null;
        trusted = false;
        removeAll();
        setVisible(false);
    }

    /** Forgets the fingerprint once the endpoint is no longer the one it was shown for. */
    void update(String endpoint) {
        if (offered != null && !endpoint.equals(offeredFor)) {
            forget();
        }
    }

    /** False while a fingerprint waits to be confirmed. */
    boolean valid() {
        return offered == null || trusted;
    }

    /** The confirmed fingerprint as a setting; empty when none was confirmed. */
    Map<String, String> settings() {
        return offered != null && trusted ? Map.of(pinKey, offered) : Map.of();
    }
}
