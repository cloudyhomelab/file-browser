package org.vaadin.filebrowser.ui.dialog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.vaadin.filebrowser.storage.SftpStorageType;
import org.vaadin.filebrowser.storage.remote.RemoteLocation;
import org.vaadin.filebrowser.ui.Segmented;
import org.vaadin.filebrowser.ui.Ui;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextArea;

/** A folder on an SSH server, signed in to with a password or a pasted private key. */
public class SftpForm extends RootForm {

    private enum Method { PASSWORD, KEY }

    private static final List<Segmented.Option<Method>> METHODS = List.of(
            Segmented.Option.text(Method.PASSWORD, "Password"),
            Segmented.Option.text(Method.KEY, "Private key"));

    private final SignInBox signIn;
    private final ServerTrust trust;
    private final Div methodPicker = Ui.div("");
    private final TextArea privateKey = new TextArea("Private key");
    private final PasswordField passphrase = new PasswordField("Key passphrase (optional)");
    private Method method = Method.PASSWORD;

    public SftpForm(Context context) {
        super(context);
        signIn = new SignInBox(context, this::field);
        trust = new ServerTrust(context, SftpStorageType.HOST_KEY, "host key");
        privateKey.addClassName("fb-field--mono");
        privateKey.addClassName("fb-addroot-key");
        privateKey.setPlaceholder("-----BEGIN OPENSSH PRIVATE KEY-----");
        passphrase.addClassName("fb-field--mono");
        field(privateKey);
        field(passphrase);
        signIn.fields(signIn.user(), methodPicker, signIn.password(), privateKey, passphrase);
        add(signIn, trust);
        if (editing()) {
            method = signIn.has(SftpStorageType.PRIVATE_KEY) ? Method.KEY : Method.PASSWORD;
            signIn.lock(signIn.user(), SftpStorageType.USER);
            signIn.lock(signIn.password(), SftpStorageType.PASSWORD);
            signIn.lock(privateKey, SftpStorageType.PRIVATE_KEY);
            signIn.lock(passphrase, SftpStorageType.PASSPHRASE);
        }
    }

    @Override
    protected String shapeProblem(String endpoint) {
        return RemoteLocation.parse(endpoint, SftpStorageType.SCHEME, SftpStorageType.DEFAULT_PORT) == null
                ? context.info().endpointError() : null;
    }

    @Override
    public void offerFingerprint(String fingerprint) {
        trust.offer(fingerprint, endpoint());
    }

    @Override
    protected void updateFields() {
        trust.update(endpoint());
        methodPicker.removeAll();
        final var choice = new Segmented<>("fb-seg--l", METHODS, method, picked -> {
            method = picked;
            context.onChange().run();
        });
        methodPicker.add(editing() ? choice.locked() : choice);
        signIn.password().setVisible(method == Method.PASSWORD);
        privateKey.setVisible(method == Method.KEY);
        passphrase.setVisible(method == Method.KEY);
        final var started = signIn.started() || !privateKey.getValue().isBlank();
        signIn.update(started, credentialsProblem(), SignInBox.STORED);
    }

    private String credentialsProblem() {
        if (SignInBox.blank(signIn.user())) {
            return "Enter the user name to sign in with.";
        }
        if (method == Method.PASSWORD && SignInBox.blank(signIn.password())) {
            return "Enter the password.";
        }
        if (method == Method.KEY && privateKey.getValue().isBlank()) {
            return "Paste the private key.";
        }
        return null;
    }

    @Override
    protected boolean fieldsValid() {
        return credentialsProblem() == null && trust.valid();
    }

    @Override
    public Map<String, String> settings() {
        return trust.settings();
    }

    @Override
    public Map<String, String> secrets() {
        final var secrets = new LinkedHashMap<String, String>();
        secrets.put(SftpStorageType.USER, signIn.user().getValue().trim());
        if (method == Method.PASSWORD) {
            secrets.put(SftpStorageType.PASSWORD, signIn.password().getValue());
        } else {
            secrets.put(SftpStorageType.PRIVATE_KEY, privateKey.getValue().strip() + "\n");
            if (!passphrase.getValue().isEmpty()) {
                secrets.put(SftpStorageType.PASSPHRASE, passphrase.getValue());
            }
        }
        return secrets;
    }
}
