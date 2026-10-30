package org.vaadin.filebrowser.ui.dialog;

import java.util.LinkedHashMap;
import java.util.Map;

import org.vaadin.filebrowser.storage.SmbStorageType;
import org.vaadin.filebrowser.ui.Ui;

import com.vaadin.flow.component.checkbox.Switch;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.Autocomplete;
import com.vaadin.flow.component.textfield.TextField;

/** A folder in a share on an SMB 3 server, signed in to with a user name and password. */
public class SmbForm extends RootForm {

    private final SignInBox signIn;
    private final TextField domain = new TextField("Domain (optional)");
    private final Switch requireEncryption;
    private final Div securityHint = Ui.div("fb-addroot-access-hint");

    public SmbForm(Context context) {
        super(context);
        signIn = new SignInBox(context, this::field);
        final var settings = editing() ? context.editing().settings() : Map.<String, String>of();
        requireEncryption = new Switch("Require encryption", SmbStorageType.requireEncryption(settings),
                e -> context.onChange().run());
        domain.addClassName("fb-field--mono");
        domain.setAutocomplete(Autocomplete.OFF);
        field(domain);
        final var securityText = Ui.span("", "Security");
        final var securityLabel = Ui.div("fb-form-label", securityText);
        final var securityGroup = Ui.div("", securityLabel, requireEncryption, securityHint);
        add(securityGroup);
        signIn.fields(signIn.getUser(), signIn.getPassword(), domain);
        add(signIn);
        if (editing()) {
            requireEncryption.setEnabled(false);
            signIn.lock(signIn.getUser(), SmbStorageType.USER);
            signIn.lock(signIn.getPassword(), SmbStorageType.PASSWORD);
            signIn.lock(domain, SmbStorageType.DOMAIN);
        }
    }

    @Override
    protected String shapeProblem(String endpoint) {
        return SmbStorageType.parse(endpoint) == null ? context.info().endpointError() : null;
    }

    @Override
    protected void updateFields() {
        securityHint.removeAll();
        securityHint.add(new Span(requireEncryption.getValue()
                ? "Every message is signed and encrypted; a server that doesn't encrypt is refused."
                : "Every message is signed, and encrypted when the server offers it."));
        signIn.update(signIn.started(), credentialsProblem(), SignInBox.STORED);
    }

    private String credentialsProblem() {
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
        return credentialsProblem() == null;
    }

    @Override
    public Map<String, String> settings() {
        return requireEncryption.getValue() ? Map.of(SmbStorageType.REQUIRE_ENCRYPTION, "true") : Map.of();
    }

    @Override
    public Map<String, String> secrets() {
        final var secrets = new LinkedHashMap<String, String>();
        secrets.put(SmbStorageType.USER, signIn.getUser().getValue().trim());
        secrets.put(SmbStorageType.PASSWORD, signIn.getPassword().getValue());
        if (!SignInBox.blank(domain)) {
            secrets.put(SmbStorageType.DOMAIN, domain.getValue().trim());
        }
        return secrets;
    }
}
