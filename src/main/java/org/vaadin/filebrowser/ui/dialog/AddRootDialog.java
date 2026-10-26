package org.vaadin.filebrowser.ui.dialog;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import org.vaadin.filebrowser.data.Access;
import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.RootType;
import org.vaadin.filebrowser.service.StorageService.PathProbe;
import org.vaadin.filebrowser.storage.MountInfo;
import org.vaadin.filebrowser.ui.Buttons;
import org.vaadin.filebrowser.ui.Icon;
import org.vaadin.filebrowser.ui.RootChip;
import org.vaadin.filebrowser.ui.Segmented;
import org.vaadin.filebrowser.ui.SubfolderLoader;
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
 * Register a folder on the server (a local disk or an OS-mounted share) as a file system. The path
 * is checked on the server as it is typed. S3 is not offered until it is supported; its fields stay
 * here for that, and S3 secrets are not kept. The same form edits an existing file system, with only
 * its name and access open to change.
 */
public class AddRootDialog extends FbDialog {

    private static final List<RootType> AVAILABLE_TYPES = List.of(RootType.DISK);
    private static final Segmented.Option<Access> READ_WRITE = Segmented.Option.text(Access.READ_WRITE, "Read & write");
    private static final Segmented.Option<Access> READ_ONLY = Segmented.Option.text(Access.READ_ONLY, "Read only");

    private final Catalog catalog;
    private final Predicate<String> endpointTaken;
    private final Function<String, PathProbe> probe;
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
    private final Div accessHint = Ui.div("fb-addroot-access-hint");
    private final Button connect;
    /** The file system being edited, or null when adding one. */
    private final FsRoot editing;
    private RootType type = RootType.DISK;
    /** The access the user picked; it applies only while the folder is writable. */
    private Access access = Access.READ_WRITE;
    private boolean writable = true;

    /** @param serverFolders lists the folders inside a server path, for the "Browse…" picker */
    public AddRootDialog(Catalog catalog, Predicate<String> endpointTaken, Function<String, PathProbe> probe,
                         SubfolderLoader serverFolders, String newId, SerializableConsumer<FsRoot> onConfirm) {
        this(catalog, endpointTaken, probe, serverFolders, newId, null, onConfirm);
    }

    /** Edits the name and access of {@code root}; its path and host stay as they are. */
    public static AddRootDialog edit(Catalog catalog, Function<String, PathProbe> probe, FsRoot root,
                                     SerializableConsumer<FsRoot> onConfirm) {
        return new AddRootDialog(catalog, endpoint -> false, probe, null, root.id(), root, onConfirm);
    }

    private AddRootDialog(Catalog catalog, Predicate<String> endpointTaken, Function<String, PathProbe> probe,
                          SubfolderLoader serverFolders, String id, FsRoot editing,
                          SerializableConsumer<FsRoot> onConfirm) {
        super(editing == null ? "Add file system" : "Edit file system", editing == null ? "plus" : "edit",
                Tone.ACCENT, Width.MEDIUM);
        this.catalog = catalog;
        this.endpointTaken = endpointTaken;
        this.probe = probe;
        this.editing = editing;
        if (editing != null) {
            type = editing.type();
            access = editing.access();
            name.setValue(editing.name());
            endpoint.setValue(editing.endpoint());
            endpoint.setReadOnly(true);
            region.setValue(editing.region());
            region.setReadOnly(true);
        }

        for (final TextFieldBase<?, String> field : new TextFieldBase[]{name, endpoint, keyId, secret, region}) {
            field.setWidthFull();
            field.addClassName("fb-field");
            field.setValueChangeMode(ValueChangeMode.EAGER);
            field.addValueChangeListener(e -> update());
            field.addKeyPressListener(Key.ENTER, e -> submit(id, onConfirm));
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
        typeGroup.setVisible(AVAILABLE_TYPES.size() > 1);
        if (serverFolders != null) {
            final var browse = Buttons.create(Buttons.Kind.SECONDARY, "Browse", "folder", () -> {
                final var picker = new FolderPickerDialog(serverFolders, endpointTaken, endpoint.getValue().trim(),
                        endpoint::setValue);
                picker.open();
            });
            browse.addClassName("fb-field-browse");
            browse.setTooltipText("Browse server folders");
            endpoint.setSuffixComponent(browse);
        }
        final var endpointGroup = Ui.div("", endpoint, endpointError);
        final var accessText = Ui.span("", "Access");
        final var accessLabel = Ui.div("fb-form-label", accessText);
        final var accessGroup = Ui.div("", accessLabel, accessPicker, accessHint);
        final var form = Ui.div("fb-form", typeGroup, name, endpointGroup, credentials, region, accessGroup);
        add(form);

        connect = Buttons.create(Buttons.Kind.PRIMARY, editing == null ? "Connect" : "Save", null, () -> submit(id, onConfirm));
        final var cancel = cancelButton();
        footer(cancel, connect);
        update();
    }

    private void update() {
        final var info = catalog.rootType(type);
        typePicker.removeAll();
        for (final var t : AVAILABLE_TYPES) {
            final var kind = catalog.kind(t.key());
            final var chip = new RootChip(t.key(), kind.icon(), RootChip.Size.S);
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
        region.setVisible(info.regionLabel() != null);
        if (info.regionLabel() != null) {
            region.setLabel(info.regionLabel() + " (optional)");
            region.setPlaceholder(info.defaultRegion());
        }

        final var ep = endpoint.getValue().trim();
        final var badShape = ep.length() > 0 && (!ep.startsWith(info.endpointPrefix()) || ep.length() <= info.endpointPrefix().length());
        final var dupe = !ep.isEmpty() && endpointTaken.test(ep);
        // an existing file system may be offline right now; that must not block renaming it
        final var folderProblem = ep.isEmpty() || badShape || dupe || type != RootType.DISK || editing != null
                ? null : folderProblem(ep);
        final var problem = dupe ? "This file system is already connected"
                : badShape ? info.endpointError()
                : folderProblem;
        endpointError.removeAll();
        endpointError.setVisible(problem != null);
        final var warnIcon = new Icon("warn");
        final var errorText = new Span(problem == null ? "" : problem);
        endpointError.add(warnIcon, errorText);
        endpoint.getElement().getClassList().set("fb-field--invalid", problem != null);

        credentials.setVisible(type == RootType.S3);
        final var credsBad = credentialsIncomplete();
        credentialsHint.removeAll();
        credentialsHint.getElement().getClassList().set("fb-field-error-text", credsBad);
        credentialsHint.add(credsBad ? "Enter both the access key ID and the secret access key."
                : "Leave blank to use the server's IAM role or public bucket access. Keys are stored encrypted and never shown again.");

        writable = problem != null || ep.isEmpty() || type != RootType.DISK || writableFolder(ep);
        final var accessOptions = writable ? List.of(READ_WRITE, READ_ONLY) : List.of(READ_ONLY);
        accessPicker.removeAll();
        final var accessChoice = new Segmented<>("fb-seg--l", accessOptions, effectiveAccess(), a -> {
            access = a;
            update();
        });
        accessPicker.add(accessChoice);
        accessHint.setText(writable ? "" : "The server can't write to this folder, so it can only be added read-only.");
        accessHint.setVisible(!writable);

        connect.setEnabled(!name.getValue().trim().isEmpty() && !ep.isEmpty() && problem == null && !credsBad);
    }

    // typing a path passes through parents such as /home that the server can't write to, so the
    // choice is kept and only overridden while the current path is read-only
    private Access effectiveAccess() {
        return writable ? access : Access.READ_ONLY;
    }

    // when editing, only a folder that is there and refuses writes limits the choice
    private boolean writableFolder(String path) {
        final var found = probe.apply(path);
        return found.writable() || editing != null && !found.exists();
    }

    private String folderProblem(String path) {
        final var found = probe.apply(path);
        if (!found.exists()) {
            return "No folder exists at this path on the server.";
        }
        if (!found.directory()) {
            return "This path is a file, not a folder.";
        }
        if (!found.readable()) {
            return "The server can't read this folder.";
        }
        return null;
    }

    private boolean credentialsIncomplete() {
        if (type != RootType.S3) {
            return false;
        }
        final var hasKey = !keyId.getValue().trim().isEmpty();
        final var hasSecret = !secret.getValue().trim().isEmpty();
        return hasKey != hasSecret;
    }

    private void submit(String id, SerializableConsumer<FsRoot> onConfirm) {
        if (!connect.isEnabled()) {
            return;
        }
        final var info = catalog.rootType(type);
        final var regionValue = info.regionLabel() == null ? ""
                : region.getValue().trim().isEmpty() ? info.defaultRegion() : region.getValue().trim();
        final var root = new FsRoot(id, name.getValue().trim(), type, endpoint.getValue().trim(), regionValue,
                null, true, effectiveAccess(), MountInfo.UNKNOWN);
        close();
        onConfirm.accept(root);
    }
}
