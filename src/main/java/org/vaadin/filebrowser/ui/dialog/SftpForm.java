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
public class SftpForm extends RemoteForm {

    private enum Method { PASSWORD, KEY }

    private static final List<Segmented.Option<Method>> METHODS = List.of(
            Segmented.Option.text(Method.PASSWORD, "Password"),
            Segmented.Option.text(Method.KEY, "Private key"));

    private final Div methodPicker = Ui.div("");
    private final TextArea privateKey = new TextArea("Private key");
    private final PasswordField passphrase = new PasswordField("Key passphrase (optional)");
    private Method method = Method.PASSWORD;

    public SftpForm(Context context) {
        super(context, SftpStorageType.HOST_KEY, "host key");
        privateKey.addClassName("fb-field--mono");
        privateKey.addClassName("fb-addroot-key");
        privateKey.setPlaceholder("-----BEGIN OPENSSH PRIVATE KEY-----");
        passphrase.addClassName("fb-field--mono");
        field(privateKey);
        field(passphrase);
        credentials.add(user, methodPicker, password, privateKey, passphrase);
        addCredentials();
    }

    @Override
    protected String shapeProblem(String endpoint) {
        return RemoteLocation.parse(endpoint, SftpStorageType.SCHEME, SftpStorageType.DEFAULT_PORT) == null
                ? context.info().endpointError() : null;
    }

    @Override
    protected void updateRemoteFields() {
        methodPicker.removeAll();
        final var choice = new Segmented<>("fb-seg--l", METHODS, method, picked -> {
            method = picked;
            context.onChange().run();
        });
        methodPicker.add(choice);
        password.setVisible(method == Method.PASSWORD);
        privateKey.setVisible(method == Method.KEY);
        passphrase.setVisible(method == Method.KEY);
    }

    @Override
    protected String credentialsProblem() {
        if (blank(user)) {
            return "Enter the user name to sign in with.";
        }
        if (method == Method.PASSWORD && blank(password)) {
            return "Enter the password.";
        }
        if (method == Method.KEY && privateKey.getValue().isBlank()) {
            return "Paste the private key.";
        }
        return null;
    }

    @Override
    protected boolean credentialsStarted() {
        return super.credentialsStarted() || !privateKey.getValue().isBlank();
    }

    @Override
    protected Map<String, String> remoteSettings() {
        return Map.of();
    }

    @Override
    public Map<String, String> secrets() {
        final var secrets = new LinkedHashMap<String, String>();
        secrets.put(SftpStorageType.USER, user.getValue().trim());
        if (method == Method.PASSWORD) {
            secrets.put(SftpStorageType.PASSWORD, password.getValue());
        } else {
            secrets.put(SftpStorageType.PRIVATE_KEY, privateKey.getValue().strip() + "\n");
            if (!passphrase.getValue().isEmpty()) {
                secrets.put(SftpStorageType.PASSPHRASE, passphrase.getValue());
            }
        }
        return secrets;
    }
}
