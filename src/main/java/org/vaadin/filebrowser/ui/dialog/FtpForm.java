package org.vaadin.filebrowser.ui.dialog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.vaadin.filebrowser.storage.FtpProvider.Security;
import org.vaadin.filebrowser.storage.FtpStorageType;
import org.vaadin.filebrowser.storage.remote.RemoteLocation;
import org.vaadin.filebrowser.ui.Icon;
import org.vaadin.filebrowser.ui.Segmented;
import org.vaadin.filebrowser.ui.Switch;
import org.vaadin.filebrowser.ui.Ui;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;

/** A folder on an FTP server, over TLS unless the admin turns it off; anonymous sign-in stores no secrets. */
public class FtpForm extends RemoteForm {

    private static final List<Segmented.Option<Security>> SECURITY = List.of(
            Segmented.Option.text(Security.EXPLICIT, "Explicit TLS"),
            Segmented.Option.text(Security.IMPLICIT, "Implicit TLS"),
            Segmented.Option.text(Security.NONE, "None"));

    private final Div securityPicker = Ui.div("");
    private final Div securityHint = Ui.div("fb-addroot-access-hint");
    private final Switch anonymous;
    private Security security = Security.EXPLICIT;

    public FtpForm(Context context) {
        super(context, FtpStorageType.CERTIFICATE, "certificate");
        final var settings = editing() ? context.editing().settings() : Map.<String, String>of();
        anonymous = new Switch("Anonymous sign-in", FtpStorageType.anonymous(settings), on -> context.onChange().run());
        final var securityText = Ui.span("", "Security");
        final var securityLabel = Ui.div("fb-form-label", securityText);
        final var securityGroup = Ui.div("", securityLabel, securityPicker, securityHint);
        add(securityGroup);
        credentials.add(anonymous, user, password);
        addCredentials();
        if (editing()) {
            security = FtpStorageType.security(settings);
            anonymous.locked();
            lockSecret(user, FtpStorageType.USER);
            lockSecret(password, FtpStorageType.PASSWORD);
        }
    }

    @Override
    protected String shapeProblem(String endpoint) {
        return RemoteLocation.parse(endpoint, FtpStorageType.SCHEME, FtpStorageType.DEFAULT_PORT) == null
                ? context.info().endpointError() : null;
    }

    @Override
    protected void updateRemoteFields() {
        securityPicker.removeAll();
        final var choice = new Segmented<>("fb-seg--l", SECURITY, security, picked -> {
            security = picked;
            // a certificate seen over TLS says nothing about the next mode
            forgetFingerprint();
            context.onChange().run();
        });
        securityPicker.add(editing() ? choice.locked() : choice);
        securityHint.removeAll();
        if (security == Security.NONE) {
            final var warnIcon = new Icon("warn");
            final var text = new Span(anonymous.isOn() ? "Nothing sent to or from the server is encrypted."
                    : "The password and files cross the network unencrypted.");
            securityHint.add(warnIcon, text);
        } else if (security == Security.IMPLICIT) {
            securityHint.add(new Span("TLS from the start, usually on port 990."));
        } else {
            securityHint.add(new Span("Switches to TLS before signing in (AUTH TLS), on the usual port 21."));
        }
        securityHint.getElement().getClassList().set("fb-field-error-text", security == Security.NONE);
        final var named = !anonymous.isOn();
        user.setVisible(named);
        password.setVisible(named);
    }

    @Override
    protected String credentialsNote() {
        return anonymous.isOn() ? "Signs in as “anonymous”; nothing is stored." : super.credentialsNote();
    }

    @Override
    protected String credentialsProblem() {
        if (anonymous.isOn()) {
            return null;
        }
        if (blank(user)) {
            return "Enter the user name to sign in with.";
        }
        if (blank(password)) {
            return "Enter the password.";
        }
        return null;
    }

    @Override
    protected Map<String, String> remoteSettings() {
        final var settings = new LinkedHashMap<String, String>();
        settings.put(FtpStorageType.SECURITY, security.key());
        if (anonymous.isOn()) {
            settings.put(FtpStorageType.ANONYMOUS, "true");
        }
        return settings;
    }

    @Override
    public Map<String, String> secrets() {
        if (anonymous.isOn()) {
            return Map.of();
        }
        return Map.of(FtpStorageType.USER, user.getValue().trim(), FtpStorageType.PASSWORD, password.getValue());
    }
}
