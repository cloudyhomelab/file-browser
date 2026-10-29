package org.vaadin.filebrowser.ui.dialog;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.vaadin.filebrowser.storage.S3Provider;
import org.vaadin.filebrowser.storage.S3StorageType;
import org.vaadin.filebrowser.ui.Icon;
import org.vaadin.filebrowser.ui.Ui;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.textfield.TextFieldBase;

/**
 * An S3 bucket, tried with its settings on Connect. The keys go straight to the server and are
 * never shown again; a bucket's endpoint URL and keys are fixed once added.
 */
public class S3Form extends RootForm {

    /** Keys of the S3 entry's {@code form} in {@code data/root-types.json}. */
    private static final String REGION_LABEL = "regionLabel";
    private static final String DEFAULT_REGION = "defaultRegion";

    private final TextField endpointUrl = new TextField("Endpoint URL (optional)");
    private final TextField keyId = new TextField("Access key ID");
    private final PasswordField secret = new PasswordField("Secret access key");
    private final Div credentialsHint = Ui.div("fb-addroot-creds-hint");
    private final TextField region = new TextField();

    public S3Form(Context context) {
        super(context);
        endpointUrl.addClassName("fb-field--mono");
        endpointUrl.setPlaceholder("https://s3.example.com — blank for AWS");
        endpointUrl.setErrorMessage("Use an http:// or https:// URL");
        keyId.addClassName("fb-field--mono");
        keyId.setPlaceholder("AKIAIOSFODNN7EXAMPLE");
        secret.addClassName("fb-field--mono");
        secret.setPlaceholder("Paste secret key");
        final var info = context.info();
        region.setLabel(info.form(REGION_LABEL) + " (optional)");
        region.setPlaceholder(info.form(DEFAULT_REGION));
        if (editing()) {
            region.setValue(context.editing().settings().getOrDefault(S3StorageType.REGION, ""));
            region.setReadOnly(true);
            endpointUrl.setValue(context.editing().settings().getOrDefault(S3StorageType.ENDPOINT_URL, ""));
            endpointUrl.setReadOnly(true);
            endpointUrl.setPlaceholder("AWS");
        }
        for (final var field : List.<TextFieldBase<?, String>>of(endpointUrl, keyId, secret, region)) {
            field(field);
        }

        final var lockIcon = new Icon("lock");
        final var credsLabel = new Span("Credentials");
        final var credsOptional = Ui.span("fb-muted-regular", "(optional)");
        final var credsTitle = Ui.div("fb-addroot-creds-title", lockIcon, credsLabel, credsOptional);
        final var credentials = Ui.div("fb-addroot-creds", credsTitle, keyId, secret, credentialsHint);
        add(endpointUrl, credentials, region);
        if (editing()) {
            // shown as stored; keys never are
            final var stored = context.workspace().secretNames(context.editing());
            for (final var key : List.<TextFieldBase<?, String>>of(keyId, secret)) {
                key.setReadOnly(true);
                key.setPlaceholder(stored.contains(S3StorageType.ACCESS_KEY) ? "Stored encrypted" : "None, a public bucket");
            }
        }
    }

    @Override
    protected String shapeProblem(String endpoint) {
        return S3Provider.Location.parse(endpoint) == null ? context.info().endpointError() : null;
    }

    @Override
    protected void updateFields() {
        endpointUrl.setInvalid(!validUrl(endpointUrl.getValue().trim()));
        final var credsBad = credentialsIncomplete();
        credentialsHint.removeAll();
        credentialsHint.getElement().getClassList().set("fb-field-error-text", credsBad);
        credentialsHint.add(credsBad ? "Enter both the access key ID and the secret access key."
                : editing() ? "Never shown again. To change them, remove the file system and add it again."
                : "Leave both blank for a public bucket. Keys are stored encrypted and never shown again.");
    }

    @Override
    protected boolean fieldsValid() {
        return validUrl(endpointUrl.getValue().trim()) && !credentialsIncomplete();
    }

    @Override
    public Map<String, String> settings() {
        final var value = region.getValue().trim();
        final var settings = new LinkedHashMap<String, String>();
        settings.put(S3StorageType.REGION, value.isEmpty() ? context.info().form(DEFAULT_REGION) : value);
        final var url = endpointUrl.getValue().trim();
        if (!url.isEmpty()) {
            settings.put(S3StorageType.ENDPOINT_URL, url);
        }
        return settings;
    }

    @Override
    public Map<String, String> secrets() {
        final var key = keyId.getValue().trim();
        if (key.isEmpty()) {
            return Map.of();
        }
        return Map.of(S3StorageType.ACCESS_KEY, key, S3StorageType.SECRET_KEY, secret.getValue().trim());
    }

    private static boolean validUrl(String url) {
        if (url.isEmpty()) {
            return true;
        }
        try {
            final var uri = URI.create(url);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean credentialsIncomplete() {
        final var hasKey = !keyId.getValue().isBlank();
        final var hasSecret = !secret.getValue().isBlank();
        return hasKey != hasSecret;
    }
}
