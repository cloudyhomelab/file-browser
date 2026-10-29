package org.vaadin.filebrowser.ui.dialog;

import java.util.List;
import java.util.Map;

import org.vaadin.filebrowser.data.Access;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.RootStatus;
import org.vaadin.filebrowser.service.Workspace;
import org.vaadin.filebrowser.storage.StorageConfig;
import org.vaadin.filebrowser.ui.Buttons;
import org.vaadin.filebrowser.ui.RootChip;
import org.vaadin.filebrowser.ui.Segmented;
import org.vaadin.filebrowser.ui.Ui;

import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.function.SerializableBiConsumer;
import com.vaadin.flow.function.SerializableConsumer;

/**
 * Register a file system of any type that has a {@link RootForm}: the dialog holds the type, name
 * and access, the type's form everything else. A new file system is tried on the server on Connect.
 * The same dialog edits an existing file system, with only its name and access open to change.
 */
public class AddRootDialog extends FbDialog {

    private static final Segmented.Option<Access> READ_WRITE = Segmented.Option.text(Access.READ_WRITE, "Read & write");
    private static final Segmented.Option<Access> READ_ONLY = Segmented.Option.text(Access.READ_ONLY, "Read only");

    private final Workspace workspace;
    private final String id;
    private final SerializableBiConsumer<FsRoot, Map<String, String>> onConfirm;
    private final List<String> types;
    private final Div typePicker = Ui.div("fb-addroot-types");
    private final TextField name = new TextField("Display name");
    private final Div formSlot = Ui.div("fb-form-section");
    private final Div accessPicker = Ui.div("");
    private final Div accessHint = Ui.div("fb-addroot-access-hint");
    private final Button connect;
    /** The file system being edited, or null when adding one. */
    private final FsRoot editing;
    private String type;
    private RootForm form;
    /** The access the user picked; it applies only while the file system is writable. */
    private Access access = Access.READ_WRITE;
    private boolean writable = true;

    /** @param onConfirm gets the new file system and what its type stores encrypted */
    public AddRootDialog(Workspace workspace, String newId, SerializableBiConsumer<FsRoot, Map<String, String>> onConfirm) {
        this(workspace, newId, null, onConfirm);
    }

    /** True when file systems of {@code type} have a form, so they can be edited. */
    public static boolean canEdit(Workspace workspace, String type) {
        return RootForms.has(type) && workspace.catalog().rootType(type) != null;
    }

    /** Edits the name and access of {@code root}; its endpoint and settings stay as they are. */
    public static AddRootDialog edit(Workspace workspace, FsRoot root, SerializableConsumer<FsRoot> onConfirm) {
        return new AddRootDialog(workspace, root.id(), root, (edited, secrets) -> onConfirm.accept(edited));
    }

    private AddRootDialog(Workspace workspace, String id, FsRoot editing,
                          SerializableBiConsumer<FsRoot, Map<String, String>> onConfirm) {
        super(editing == null ? "Add file system" : "Edit file system", editing == null ? "plus" : "edit",
                Tone.ACCENT, Width.MEDIUM);
        this.workspace = workspace;
        this.id = id;
        this.editing = editing;
        this.onConfirm = onConfirm;
        this.types = editing != null ? List.of(editing.type())
                : workspace.catalog().rootTypeKeys().stream().filter(RootForms::has).toList();
        if (editing != null) {
            access = editing.access();
            name.setValue(editing.name());
        }

        name.setWidthFull();
        name.addClassName("fb-field");
        name.setValueChangeMode(ValueChangeMode.EAGER);
        name.addValueChangeListener(e -> update());
        name.addKeyPressListener(Key.ENTER, e -> submit());
        name.setAutofocus(true);

        final var typeText = Ui.span("", "Type");
        final var typeLabel = Ui.div("fb-form-label", typeText);
        final var typeGroup = Ui.div("", typeLabel, typePicker);
        // the type of an existing file system is shown, not changed
        typeGroup.setVisible(types.size() > 1 || editing != null);
        final var accessText = Ui.span("", "Access");
        final var accessLabel = Ui.div("fb-form-label", accessText);
        final var accessGroup = Ui.div("", accessLabel, accessPicker, accessHint);
        final var fields = Ui.div("fb-form", typeGroup, name, formSlot, accessGroup);
        add(fields);

        connect = Buttons.create(Buttons.Kind.PRIMARY, editing == null ? "Connect" : "Save", null, this::submit);
        final var cancel = cancelButton();
        footer(cancel, connect);
        pick(types.getFirst());
    }

    private void pick(String picked) {
        type = picked;
        final var context = new RootForm.Context(workspace.catalog().rootType(type), editing, workspace,
                this::update, this::submit);
        form = RootForms.create(type, context);
        formSlot.removeAll();
        formSlot.add(form);
        update();
    }

    private void update() {
        final var catalog = workspace.catalog();
        final var info = catalog.rootType(type);
        typePicker.removeAll();
        for (final var t : types) {
            final var kind = catalog.kind(t);
            final var chip = new RootChip(kind, RootChip.Size.S);
            final var typeName = Ui.span("", catalog.rootType(t).displayName());
            final var card = Ui.button("fb-addroot-type" + (t.equals(type) ? " fb-addroot-type--on" : ""),
                    () -> pick(t), chip, typeName);
            final var pressed = String.valueOf(t.equals(type));
            card.getElement().setAttribute("aria-pressed", pressed);
            typePicker.add(card);
        }
        name.setPlaceholder(info.namePlaceholder());

        form.update();
        writable = form.writable();
        final var accessOptions = writable ? List.of(READ_WRITE, READ_ONLY) : List.of(READ_ONLY);
        accessPicker.removeAll();
        final var accessChoice = new Segmented<>("fb-seg--l", accessOptions, effectiveAccess(), a -> {
            access = a;
            update();
        });
        accessPicker.add(accessChoice);
        accessHint.setText(writable ? "" : form.readOnlyHint());
        accessHint.setVisible(!writable);

        connect.setEnabled(!name.getValue().trim().isEmpty() && form.complete());
    }

    // typing a path passes through parents such as /home that the server can't write to, so the
    // choice is kept and only overridden while the current path is read-only
    private Access effectiveAccess() {
        return writable ? access : Access.READ_ONLY;
    }

    private void submit() {
        if (!connect.isEnabled()) {
            return;
        }
        // only the name and access of an existing file system change; its settings stay as stored
        final var settings = editing == null ? form.settings() : editing.settings();
        final var options = editing == null ? workspace.catalog().rootOptions() : editing.options();
        final var root = new FsRoot(id, name.getValue().trim(), type, form.endpoint(), settings,
                effectiveAccess(), options, RootStatus.UNKNOWN);
        final var secrets = editing == null ? form.secrets() : Map.<String, String>of();
        if (editing == null) {
            final var check = workspace.checkRoot(type, new StorageConfig(root.endpoint(), root.settings(), secrets));
            if (check.fingerprint() != null) {
                form.offerFingerprint(check.fingerprint());
                return;
            }
            if (check.problem() != null) {
                form.showConnectProblem(check.problem());
                return;
            }
        }
        close();
        onConfirm.accept(root, secrets);
    }
}
