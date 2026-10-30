package org.vaadin.filebrowser.ui.dialog;

import java.util.function.IntFunction;

import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.RootOptionLimits;
import org.vaadin.filebrowser.data.RootOptions;
import org.vaadin.filebrowser.ui.Buttons;
import org.vaadin.filebrowser.ui.Switch;
import org.vaadin.filebrowser.ui.Ui;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.tabs.TabSheetVariant;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.function.SerializableConsumer;

/** What admins allow on one file system, besides its access. Saved changes apply at once, for everyone. */
public class RootSettingsDialog extends FbDialog {

    private final RootOptionLimits limits;
    private final TabSheet tabs = new TabSheet();
    private final IntegerField maxConnections;
    private final IntegerField browsingReserve;
    private final IntegerField idleStream;
    private final Switch linkUploads;
    private final Switch extraction;
    private final Switch opening;

    public RootSettingsDialog(FsRoot root, RootOptionLimits limits, SerializableConsumer<RootOptions> onSave) {
        super("Settings for “" + root.name() + "”", "cog", Tone.ACCENT, Width.MEDIUM);
        this.limits = limits;
        final var options = root.options();
        maxConnections = number("Max connections", options.maxConnections(), null);
        maxConnections.setMin(limits.maxConnections().min());
        maxConnections.setMax(limits.maxConnections().max());
        browsingReserve = number("Kept for browsing", options.browsingReserve(), null);
        browsingReserve.setMin(limits.minBrowsingReserve());
        browsingReserve.setMax(limits.maxBrowsingReserve(options.maxConnections()));
        maxConnections.addValueChangeListener(e -> {
            if (e.getValue() != null) {
                browsingReserve.setMax(limits.maxBrowsingReserve(e.getValue()));
            }
        });
        idleStream = number("Idle timeout", options.idleStreamSeconds(), "s");
        idleStream.setMin(limits.idleStreamSeconds().min());
        idleStream.setMax(limits.idleStreamSeconds().max());
        final var connectionFields = Ui.div("fb-settings-numbers", maxConnections, browsingReserve, idleStream);
        final var connectionsHint = note("Sessions, HTTP connections or open files used at once. Viewing, "
                + "downloading, uploading and copying never take the ones kept for browsing. A file viewed in the page "
                + "that the browser stops reading is closed after the idle timeout.");
        final var connections = Ui.div("fb-settings-panel", connectionFields, connectionsHint);

        linkUploads = new Switch("Allow uploads from web links", options.linkUploads(), on -> { });
        extraction = new Switch("Allow archive extraction", options.extraction(), on -> { });
        opening = new Switch("Allow opening files", options.opening(), on -> { });
        final var features = Ui.div("fb-settings-panel",
                option(linkUploads, "The server downloads files from links people paste into the upload dialog."),
                option(extraction, "Archives can be extracted, and compressed files decompressed, as they are "
                        + "uploaded."),
                option(opening, "Files can be viewed in the page. Downloading is always allowed."));
        tabs.addClassName("fb-settings-tabs");
        tabs.addThemeVariants(TabSheetVariant.NO_PADDING);
        tabs.add("Features", features);
        final var connectionsTab = tabs.add("Connections", connections);
        add(tabs);
        final var save = Buttons.create(Buttons.Kind.PRIMARY, "Save", null, () -> {
            if (!valid()) {
                tabs.setSelectedTab(connectionsTab);
                return;
            }
            close();
            final var saved = new RootOptions(linkUploads.isOn(), extraction.isOn(), opening.isOn(),
                    maxConnections.getValue(), browsingReserve.getValue(), idleStream.getValue());
            onSave.accept(saved);
        });
        footer(cancelButton(), save);
    }

    private static IntegerField number(String label, int value, String unit) {
        final var field = new IntegerField(label);
        field.addClassName("fb-field");
        field.setValue(value);
        field.setStepButtonsVisible(true);
        if (unit != null) {
            final var suffix = Ui.span("fb-field-unit", unit);
            field.setSuffixComponent(suffix);
        }
        field.addValueChangeListener(e -> field.setInvalid(false));
        return field;
    }

    private boolean valid() {
        final var connections = maxConnections.getValue();
        final var connectionsOk = check(maxConnections, limits::maxConnectionsProblem);
        final var reserveOk = connections != null
                && check(browsingReserve, reserve -> limits.browsingReserveProblem(connections, reserve));
        final var idleOk = check(idleStream, limits::idleStreamProblem);
        return connectionsOk && reserveOk && idleOk;
    }

    /** Marks the field with what is wrong with its value; true when nothing is. */
    private static boolean check(IntegerField field, IntFunction<String> problem) {
        final var value = field.getValue();
        // an empty field is out of range, so it shows the same bounds
        final var message = problem.apply(value == null ? Integer.MIN_VALUE : value);
        field.setErrorMessage(message);
        field.setInvalid(message != null);
        return message == null;
    }

    private static Div option(Switch toggle, String hint) {
        final var text = hint(hint);
        return Ui.div("fb-settings-option", toggle, text);
    }

    private static Div hint(String text) {
        final var span = Ui.span("", text);
        return Ui.div("fb-settings-hint", span);
    }

    private static Div note(String text) {
        final var span = Ui.span("", text);
        return Ui.div("fb-settings-note", span);
    }
}
