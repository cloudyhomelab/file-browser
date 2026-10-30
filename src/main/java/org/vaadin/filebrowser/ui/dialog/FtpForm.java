package org.vaadin.filebrowser.ui.dialog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.vaadin.filebrowser.storage.FtpProvider.Security;
import org.vaadin.filebrowser.storage.FtpStorageType;
import org.vaadin.filebrowser.storage.remote.RemoteLocation;
import org.vaadin.filebrowser.ui.Icon;
import org.vaadin.filebrowser.ui.Segmented;
import org.vaadin.filebrowser.ui.Ui;

import com.vaadin.flow.component.checkbox.Switch;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;

/** A folder on an FTP server, over TLS unless the admin turns it off; anonymous sign-in stores no secrets. */
public class FtpForm extends RootForm {

    private static final List<Segmented.Option<Security>> SECURITY = List.of(
            Segmented.Option.text(Security.EXPLICIT, "Explicit TLS"),
            Segmented.Option.text(Security.IMPLICIT, "Implicit TLS"),
            Segmented.Option.text(Security.NONE, "None"));

    private final SignInBox signIn;
    private final ServerTrust trust;
    private final Div securityPicker = Ui.div("");
    private final Div securityHint = Ui.div("fb-addroot-access-hint");
    private final Switch anonymous;
    private Security security = Security.EXPLICIT;

    public FtpForm(Context context) {
        super(context);
        signIn = new SignInBox(context, this::field);
        trust = new ServerTrust(context, FtpStorageType.CERTIFICATE, "certificate");
        final var settings = editing() ? context.editing().settings() : Map.<String, String>of();
        anonymous = new Switch("Anonymous sign-in", FtpStorageType.isAnonymous(settings), e -> context.onChange().run());
        final var securityText = Ui.span("", "Security");
        final var securityLabel = Ui.div("fb-form-label", securityText);
        final var securityGroup = Ui.div("", securityLabel, securityPicker, securityHint);
        add(securityGroup);
        signIn.fields(anonymous, signIn.getUser(), signIn.getPassword());
        add(signIn, trust);
        if (editing()) {
            security = FtpStorageType.securityOf(settings);
            anonymous.setEnabled(false);
            signIn.lock(signIn.getUser(), FtpStorageType.USER);
            signIn.lock(signIn.getPassword(), FtpStorageType.PASSWORD);
        }
    }

    @Override
    protected String shapeProblem(String endpoint) {
        return RemoteLocation.parse(endpoint, FtpStorageType.SCHEME, FtpStorageType.DEFAULT_PORT) == null
                ? context.info().endpointError() : null;
    }

    @Override
    public void offerFingerprint(String fingerprint) {
        trust.offer(fingerprint, endpoint());
    }

    @Override
    protected void updateFields() {
        trust.update(endpoint());
        securityPicker.removeAll();
        final var choice = new Segmented<>("fb-seg--l", SECURITY, security, picked -> {
            security = picked;
            // a certificate seen over TLS says nothing about the next mode
            trust.forget();
            context.onChange().run();
        });
        securityPicker.add(editing() ? choice.locked() : choice);
        securityHint.removeAll();
        if (security == Security.NONE) {
            final var warnIcon = new Icon("warn");
            final var text = new Span(anonymous.getValue() ? "Nothing sent to or from the server is encrypted."
                    : "The password and files cross the network unencrypted.");
            securityHint.add(warnIcon, text);
        } else if (security == Security.IMPLICIT) {
            securityHint.add(new Span("TLS from the start, usually on port 990."));
        } else {
            securityHint.add(new Span("Switches to TLS before signing in (AUTH TLS), on the usual port 21."));
        }
        securityHint.getElement().getClassList().set("fb-field-error-text", security == Security.NONE);
        final var named = !anonymous.getValue();
        signIn.getUser().setVisible(named);
        signIn.getPassword().setVisible(named);
        final var note = anonymous.getValue() ? "Signs in as “anonymous”; nothing is stored."
                : SignInBox.STORED;
        signIn.update(signIn.started(), credentialsProblem(), note);
    }

    private String credentialsProblem() {
        if (anonymous.getValue()) {
            return null;
        }
        if (SignInBox.blank(signIn.getUser())) {
            return "Enter the user name to sign in with.";
        }
        if (SignInBox.blank(signIn.getPassword())) {
            return "Enter the password.";
        }
        return null;
    }

    @Override
    protected boolean fieldsValid() {
        return credentialsProblem() == null && trust.valid();
    }

    @Override
    public Map<String, String> settings() {
        final var settings = new LinkedHashMap<String, String>();
        settings.put(FtpStorageType.SECURITY, security.key());
        if (anonymous.getValue()) {
            settings.put(FtpStorageType.ANONYMOUS, "true");
        }
        settings.putAll(trust.settings());
        return settings;
    }

    @Override
    public Map<String, String> secrets() {
        if (anonymous.getValue()) {
            return Map.of();
        }
        return Map.of(FtpStorageType.USER, signIn.getUser().getValue().trim(), FtpStorageType.PASSWORD,
                signIn.getPassword().getValue());
    }
}
