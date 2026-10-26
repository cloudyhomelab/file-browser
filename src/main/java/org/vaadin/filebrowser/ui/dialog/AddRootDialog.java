package org.vaadin.filebrowser.ui.dialog;

import java.util.List;
import java.util.function.Predicate;

import org.vaadin.filebrowser.data.Access;
import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.RootType;
import org.vaadin.filebrowser.ui.Buttons;
import org.vaadin.filebrowser.ui.Icon;
import org.vaadin.filebrowser.ui.RootChip;
import org.vaadin.filebrowser.ui.Segmented;
import org.vaadin.filebrowser.ui.Ui;

import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.textfield.TextFieldBase;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.function.SerializableConsumer;

/**
 * Connect a disk, NFS export or S3 bucket. The connection is simulated: the new root starts empty,
 * and S3 secrets are not kept (only a masked key id would be shown, as in the design).
 */
public class AddRootDialog extends FbDialog {

    private static final List<Segmented.Option<Access>> ACCESS_OPTIONS = List.of(
            Segmented.Option.text(Access.READ_WRITE, "Read & write"),
            Segmented.Option.text(Access.READ_ONLY, "Read only"));

    private final Catalog catalog;
    private final Predicate<String> endpointTaken;
    private final Div typePicker = Ui.div("fb-addroot-types");
    private final TextField name = new TextField("Display name");
    private final TextField endpoint = new TextField();
    private final Div endpointError = Ui.div("fb-field-error");
    private final Div credentials = Ui.div("fb-addroot-creds");
    private final TextField keyId = new TextField("Access key ID");
    private final PasswordField secret = new PasswordField("Secret access key");
    private final Div credentialsHint = Ui.div("fb-addroot-creds-hint");
    private final TextField region = new TextField();
    private final Div accessPicker = Ui.div("");
    private final Button connect;
    private RootType type = RootType.DISK;
    private Access access = Access.READ_WRITE;

    public AddRootDialog(Catalog catalog, Predicate<String> endpointTaken, String newId,
                         SerializableConsumer<FsRoot> onConfirm) {
        super("Add file system", "plus", Tone.ACCENT, Width.MEDIUM);
        this.catalog = catalog;
        this.endpointTaken = endpointTaken;

        for (final TextFieldBase<?, String> field : new TextFieldBase[]{name, endpoint, keyId, secret, region}) {
            field.setWidthFull();
            field.addClassName("fb-field");
            field.setValueChangeMode(ValueChangeMode.EAGER);
            field.addValueChangeListener(e -> update());
            field.addKeyPressListener(Key.ENTER, e -> submit(newId, onConfirm));
        }
        name.setAutofocus(true);
        endpoint.addClassName("fb-field--mono");
        keyId.addClassName("fb-field--mono");
        keyId.setPlaceholder("AKIAIOSFODNN7EXAMPLE");
        secret.addClassName("fb-field--mono");
        secret.setPlaceholder("Paste secret key");

        final var lockIcon = new Icon("lock");
        final var credsLabel = new Span("Credentials");
        final var credsOptional = Ui.span("fb-muted-regular", "(optional)");
        final var credsTitle = Ui.div("fb-addroot-creds-title", lockIcon, credsLabel, credsOptional);
        credentials.add(credsTitle, keyId, secret, credentialsHint);

        final var typeText = Ui.span("", "Type");
        final var typeLabel = Ui.div("fb-form-label", typeText);
        final var typeGroup = Ui.div("", typeLabel, typePicker);
        final var endpointGroup = Ui.div("", endpoint, endpointError);
        final var accessText = Ui.span("", "Access");
        final var accessLabel = Ui.div("fb-form-label", accessText);
        final var accessGroup = Ui.div("", accessLabel, accessPicker);
        final var form = Ui.div("fb-form", typeGroup, name, endpointGroup, credentials, region, accessGroup);
        add(form);

        connect = Buttons.create(Buttons.Kind.PRIMARY, "Connect", null, () -> submit(newId, onConfirm));
        final var cancel = cancelButton();
        footer(cancel, connect);
        update();
    }

    private void update() {
        final var info = catalog.rootType(type);
        typePicker.removeAll();
        for (final var t : RootType.values()) {
            final var chip = new RootChip(t, RootChip.Size.S);
            final var typeName = Ui.span("", catalog.rootType(t).displayName());
            final var card = Ui.button("fb-addroot-type" + (t == type ? " fb-addroot-type--on" : ""), () -> {
                type = t;
                update();
            }, chip, typeName);
            final var pressed = String.valueOf(t == type);
            card.getElement().setAttribute("aria-pressed", pressed);
            typePicker.add(card);
        }
        name.setPlaceholder(info.namePlaceholder());
        endpoint.setLabel(info.endpointLabel());
        endpoint.setPlaceholder(info.endpointPlaceholder());
        region.setLabel(info.regionLabel() + " (optional)");
        region.setPlaceholder(info.defaultRegion());

        final var ep = endpoint.getValue().trim();
        final var badShape = ep.length() > 0 && (!ep.startsWith(info.endpointPrefix()) || ep.length() <= info.endpointPrefix().length());
        final var dupe = !ep.isEmpty() && endpointTaken.test(ep);
        endpointError.removeAll();
        endpointError.setVisible(badShape || dupe);
        final var warnIcon = new Icon("warn");
        final var errorText = new Span(dupe ? "This file system is already connected" : info.endpointError());
        endpointError.add(warnIcon, errorText);
        endpoint.getElement().getClassList().set("fb-field--invalid", badShape || dupe);

        credentials.setVisible(type == RootType.S3);
        final var credsBad = credentialsIncomplete();
        credentialsHint.removeAll();
        credentialsHint.getElement().getClassList().set("fb-field-error-text", credsBad);
        credentialsHint.add(credsBad ? "Enter both the access key ID and the secret access key."
                : "Leave blank to use the server's IAM role or public bucket access. Keys are stored encrypted and never shown again.");

        accessPicker.removeAll();
        final var accessChoice = new Segmented<>("fb-seg--l", ACCESS_OPTIONS, access, a -> {
            access = a;
            update();
        });
        accessPicker.add(accessChoice);

        connect.setEnabled(!name.getValue().trim().isEmpty() && !ep.isEmpty() && !badShape && !dupe && !credsBad);
    }

    private boolean credentialsIncomplete() {
        if (type != RootType.S3) {
            return false;
        }
        final var hasKey = !keyId.getValue().trim().isEmpty();
        final var hasSecret = !secret.getValue().trim().isEmpty();
        return hasKey != hasSecret;
    }

    private void submit(String newId, SerializableConsumer<FsRoot> onConfirm) {
        if (!connect.isEnabled()) {
            return;
        }
        final var info = catalog.rootType(type);
        final var regionValue = region.getValue().trim().isEmpty() ? info.defaultRegion() : region.getValue().trim();
        final var root = new FsRoot(newId, name.getValue().trim(), type, endpoint.getValue().trim(), regionValue,
                info.newCapacity(), true, access);
        close();
        onConfirm.accept(root);
    }
}
