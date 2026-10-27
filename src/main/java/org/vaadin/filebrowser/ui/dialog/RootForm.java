package org.vaadin.filebrowser.ui.dialog;

import java.io.Serializable;
import java.util.Map;

import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.RootTypeInfo;
import org.vaadin.filebrowser.service.Workspace;
import org.vaadin.filebrowser.ui.Icon;
import org.vaadin.filebrowser.ui.Ui;

import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.textfield.TextFieldBase;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.function.SerializableRunnable;

/**
 * The part of {@link AddRootDialog} that belongs to one storage type: its endpoint and whatever
 * else the type needs. The endpoint is checked for its shape and for being connected already;
 * subclasses add their own fields and checks. When editing, everything here is read only.
 */
public abstract class RootForm extends Div {

    /**
     * @param editing  the file system being edited, or null when adding one
     * @param onChange called on every change, so the dialog can update
     * @param onSubmit called when Enter is pressed in a field
     */
    public record Context(RootTypeInfo info, FsRoot editing, Workspace workspace, SerializableRunnable onChange,
                          SerializableRunnable onSubmit) implements Serializable {
    }

    protected final Context context;
    protected final TextField endpoint = new TextField();
    private final Div endpointError = Ui.div("fb-field-error");
    /** Why the last Connect failed, shown until the settings change. */
    private String connectProblem;
    private String problem;

    protected RootForm(Context context) {
        this.context = context;
        addClassName("fb-form-section");
        final var info = context.info();
        endpoint.setLabel(info.endpointLabel());
        endpoint.setPlaceholder(info.endpointPlaceholder());
        endpoint.addClassName("fb-field--mono");
        // filled in before listening: the dialog can't update until the form exists
        if (editing()) {
            endpoint.setValue(context.editing().endpoint());
            endpoint.setReadOnly(true);
        }
        field(endpoint);
        final var endpointGroup = Ui.div("", endpoint, endpointError);
        add(endpointGroup);
    }

    /** Sets up a field like every other in the dialog. */
    protected void field(TextFieldBase<?, String> field) {
        field.setWidthFull();
        field.addClassName("fb-field");
        field.setValueChangeMode(ValueChangeMode.EAGER);
        field.addValueChangeListener(e -> context.onChange().run());
        field.addKeyPressListener(Key.ENTER, e -> context.onSubmit().run());
    }

    protected boolean editing() {
        return context.editing() != null;
    }

    public String endpoint() {
        return endpoint.getValue().trim();
    }

    /** Checks the fields again and shows what is wrong; the dialog calls it on every change. */
    public void update() {
        final var ep = endpoint();
        final var shape = ep.isEmpty() ? null : shapeProblem(ep);
        final var dupe = !ep.isEmpty() && !editing() && context.workspace().endpointTaken(ep);
        // an existing file system may be offline right now; that must not block renaming it
        final var live = ep.isEmpty() || shape != null || dupe || editing() ? null : endpointProblem(ep);
        problem = dupe ? "This file system is already connected"
                : shape != null ? shape
                : live != null ? live
                : connectProblem;
        endpointError.removeAll();
        endpointError.setVisible(problem != null);
        final var warnIcon = new Icon("warn");
        final var errorText = new Span(problem == null ? "" : problem);
        endpointError.add(warnIcon, errorText);
        endpoint.getElement().getClassList().set("fb-field--invalid", problem != null);
        updateFields();
    }

    /** Shows why connecting failed, until the next change. */
    public void showConnectProblem(String connectProblem) {
        this.connectProblem = connectProblem;
        context.onChange().run();
        this.connectProblem = null;
    }

    /**
     * The server showed a key or certificate nobody has confirmed. Types that pin one let the admin
     * confirm it; for the rest it is just a reason connecting failed.
     */
    public void offerFingerprint(String fingerprint) {
        showConnectProblem("The server's identity can't be checked.");
    }

    /** What is wrong with the endpoint, as last shown; null when nothing is. */
    protected String problem() {
        return problem;
    }

    /** True when everything needed to connect is entered and valid. */
    public boolean complete() {
        // an existing file system keeps its settings and secrets, so the type's own fields don't apply
        return !endpoint().isEmpty() && problem == null && (editing() || fieldsValid());
    }

    /** Whether the file system can be written to, which decides if read & write can be chosen. */
    public boolean writable() {
        return true;
    }

    /** Why only read-only can be chosen, shown while {@link #writable()} is false. */
    public String readOnlyHint() {
        return "The server can't write to this file system, so it can only be added read-only.";
    }

    /** What the type stores as is, besides the endpoint; asked only when adding. */
    public abstract Map<String, String> settings();

    /** What the type stores encrypted; empty for nothing. Asked only when adding. */
    public Map<String, String> secrets() {
        return Map.of();
    }

    /** What is wrong with the form of a non-empty endpoint; null when nothing is. By default it must start with the type's prefix. */
    protected String shapeProblem(String endpoint) {
        final var info = context.info();
        final var prefix = info.endpointPrefix();
        return endpoint.startsWith(prefix) && endpoint.length() > prefix.length() ? null : info.endpointError();
    }

    /** What is wrong with a well-formed endpoint, checked as it is typed when adding; null when nothing is. */
    protected String endpointProblem(String endpoint) {
        return null;
    }

    /** Updates the type's own fields. */
    protected void updateFields() {
    }

    /** True when the type's own fields are valid. */
    protected boolean fieldsValid() {
        return true;
    }
}
