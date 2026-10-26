package org.vaadin.filebrowser.ui.dialog;

import java.io.Serializable;
import java.net.URI;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import org.vaadin.filebrowser.data.Access;
import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.RootType;
import org.vaadin.filebrowser.data.S3Settings;
import org.vaadin.filebrowser.service.StorageService.PathProbe;
import org.vaadin.filebrowser.storage.MountInfo;
import org.vaadin.filebrowser.storage.S3Provider;
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
import com.vaadin.flow.function.SerializableBiConsumer;
import com.vaadin.flow.function.SerializableConsumer;

/**
 * Register a folder on the server (a local disk or an OS-mounted share) or an S3 bucket as a file
 * system. A folder path is checked on the server as it is typed; a bucket is tried with its settings
 * on Connect. S3 keys go straight to the server and are never shown again. The same form edits an
 * existing file system, with only its name and access open to change.
 */
public class AddRootDialog extends FbDialog {

    /** Tries a bucket with the entered settings on the server. */
    @FunctionalInterface
    public interface S3Check extends Serializable {

        /** @return why the bucket can't be used, or null when it answers */
        String problem(String endpoint, String region, S3Settings settings);
    }

    private static final List<RootType> AVAILABLE_TYPES = List.of(RootType.DISK, RootType.S3);
    private static final Segmented.Option<Access> READ_WRITE = Segmented.Option.text(Access.READ_WRITE, "Read & write");
    private static final Segmented.Option<Access> READ_ONLY = Segmented.Option.text(Access.READ_ONLY, "Read only");

    private final Catalog catalog;
    private final Predicate<String> endpointTaken;
    private final Function<String, PathProbe> probe;
    private final Div typePicker = Ui.div("fb-addroot-types");
    private final TextField name = new TextField("Display name");
    private final TextField endpoint = new TextField();
    private final Div endpointError = Ui.div("fb-field-error");
    private final TextField endpointUrl = new TextField("Endpoint URL (optional)");
    private final Div credentials = Ui.div("fb-addroot-creds");
    private final TextField keyId = new TextField("Access key ID");
    private final PasswordField secret = new PasswordField("Secret access key");
    private final Div credentialsHint = Ui.div("fb-addroot-creds-hint");
    private final TextField region = new TextField();
    private final Div accessPicker = Ui.div("");
    private final Div accessHint = Ui.div("fb-addroot-access-hint");
    private final Button connect;
    private final S3Check s3Check;
    private Button browse;
    /** Why the last Connect failed, shown until the settings change. */
    private String connectProblem;
    /** The file system being edited, or null when adding one. */
    private final FsRoot editing;
    private RootType type = RootType.DISK;
    /** The access the user picked; it applies only while the folder is writable. */
    private Access access = Access.READ_WRITE;
    private boolean writable = true;

    /**
     * @param serverFolders lists the folders inside a server path, for the "Browse…" picker
     * @param onConfirm     gets the new file system and, for a bucket, its endpoint and keys
     */
    public AddRootDialog(Catalog catalog, Predicate<String> endpointTaken, Function<String, PathProbe> probe,
                         SubfolderLoader serverFolders, S3Check s3Check, String newId,
                         SerializableBiConsumer<FsRoot, S3Settings> onConfirm) {
        this(catalog, endpointTaken, probe, serverFolders, s3Check, newId, null, onConfirm);
    }

    /** Edits the name and access of {@code root}; its path and host stay as they are. */
    public static AddRootDialog edit(Catalog catalog, Function<String, PathProbe> probe, FsRoot root,
                                     SerializableConsumer<FsRoot> onConfirm) {
        return new AddRootDialog(catalog, endpoint -> false, probe, null, null, root.id(), root,
                (edited, s3) -> onConfirm.accept(edited));
    }

    private AddRootDialog(Catalog catalog, Predicate<String> endpointTaken, Function<String, PathProbe> probe,
                          SubfolderLoader serverFolders, S3Check s3Check, String id, FsRoot editing,
                          SerializableBiConsumer<FsRoot, S3Settings> onConfirm) {
        super(editing == null ? "Add file system" : "Edit file system", editing == null ? "plus" : "edit",
                Tone.ACCENT, Width.MEDIUM);
        this.catalog = catalog;
        this.endpointTaken = endpointTaken;
        this.probe = probe;
        this.editing = editing;
        this.s3Check = s3Check;
        if (editing != null) {
            type = editing.type();
            access = editing.access();
            name.setValue(editing.name());
            endpoint.setValue(editing.endpoint());
            endpoint.setReadOnly(true);
            region.setValue(editing.region());
            region.setReadOnly(true);
        }

        for (final TextFieldBase<?, String> field : new TextFieldBase[]{name, endpoint, endpointUrl, keyId, secret, region}) {
            field.setWidthFull();
            field.addClassName("fb-field");
            field.setValueChangeMode(ValueChangeMode.EAGER);
            field.addValueChangeListener(e -> update());
            field.addKeyPressListener(Key.ENTER, e -> submit(id, onConfirm));
        }
        name.setAutofocus(true);
        endpoint.addClassName("fb-field--mono");
        endpointUrl.addClassName("fb-field--mono");
        endpointUrl.setPlaceholder("https://s3.example.com — blank for AWS");
        endpointUrl.setErrorMessage("Use an http:// or https:// URL");
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
            browse = Buttons.create(Buttons.Kind.SECONDARY, "Browse", "folder", () -> {
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
        final var form = Ui.div("fb-form", typeGroup, name, endpointGroup, endpointUrl, credentials, region, accessGroup);
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
        final var s3 = type == RootType.S3;
        final var badShape = ep.length() > 0 && (s3 ? S3Provider.Location.parse(ep) == null
                : !ep.startsWith(info.endpointPrefix()) || ep.length() <= info.endpointPrefix().length());
        final var dupe = !ep.isEmpty() && endpointTaken.test(ep);
        // an existing file system may be offline right now; that must not block renaming it
        final var folderProblem = ep.isEmpty() || badShape || dupe || type != RootType.DISK || editing != null
                ? null : folderProblem(ep);
        final var problem = dupe ? "This file system is already connected"
                : badShape ? info.endpointError()
                : folderProblem != null ? folderProblem
                : connectProblem;
        endpointError.removeAll();
        endpointError.setVisible(problem != null);
        final var warnIcon = new Icon("warn");
        final var errorText = new Span(problem == null ? "" : problem);
        endpointError.add(warnIcon, errorText);
        endpoint.getElement().getClassList().set("fb-field--invalid", problem != null);

        if (browse != null) {
            browse.setVisible(!s3);
        }
        // a bucket's endpoint and keys are fixed once added
        endpointUrl.setVisible(s3 && editing == null);
        final var urlBad = s3 && !validUrl(endpointUrl.getValue().trim());
        endpointUrl.setInvalid(urlBad);
        credentials.setVisible(s3 && editing == null);
        final var credsBad = credentialsIncomplete();
        credentialsHint.removeAll();
        credentialsHint.getElement().getClassList().set("fb-field-error-text", credsBad);
        credentialsHint.add(credsBad ? "Enter both the access key ID and the secret access key."
                : "Leave both blank for a public bucket. Keys are stored encrypted and never shown again.");

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

        connect.setEnabled(!name.getValue().trim().isEmpty() && !ep.isEmpty() && problem == null && !credsBad && !urlBad);
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
        if (type != RootType.S3) {
            return false;
        }
        final var hasKey = !keyId.getValue().trim().isEmpty();
        final var hasSecret = !secret.getValue().trim().isEmpty();
        return hasKey != hasSecret;
    }

    private void submit(String id, SerializableBiConsumer<FsRoot, S3Settings> onConfirm) {
        if (!connect.isEnabled()) {
            return;
        }
        final var info = catalog.rootType(type);
        final var regionValue = info.regionLabel() == null ? ""
                : region.getValue().trim().isEmpty() ? info.defaultRegion() : region.getValue().trim();
        final var root = new FsRoot(id, name.getValue().trim(), type, endpoint.getValue().trim(), regionValue,
                null, true, effectiveAccess(), MountInfo.UNKNOWN);
        final var s3 = type == RootType.S3 && editing == null ? s3Settings() : null;
        if (s3 != null) {
            connectProblem = s3Check.problem(root.endpoint(), regionValue, s3);
            if (connectProblem != null) {
                update();
                // the problem stays until the settings change
                connectProblem = null;
                return;
            }
        }
        close();
        onConfirm.accept(root, s3);
    }

    private S3Settings s3Settings() {
        final var url = endpointUrl.getValue().trim();
        final var key = keyId.getValue().trim();
        final var secretValue = secret.getValue().trim();
        return new S3Settings(url.isEmpty() ? null : url, key.isEmpty() ? null : key,
                secretValue.isEmpty() ? null : secretValue);
    }
}
