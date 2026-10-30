package org.vaadin.filebrowser.ui;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.function.SerializableConsumer;
import com.vaadin.flow.function.SerializableRunnable;

/** Floating dark pill at the bottom of the file area while items are selected. */
public class BulkBar extends Composite<Div> {

    public BulkBar(int count, boolean readOnly, SerializableConsumer<EntryAction> onAction, SerializableRunnable onClear) {
        final var div = getContent();
        div.setClassName("fb-bulkbar");
        final var countText = String.valueOf(count);
        final var pill = Ui.span("fb-pill-count", countText);
        final var selectedLabel = Ui.span("", "selected");
        final var counter = Ui.span("fb-bulkbar-count", pill, selectedLabel);
        final var leadingSeparator = Ui.divider("fb-bulkbar-sep");
        final var download = action("download", "Download", EntryAction.DOWNLOAD, false, false, onAction);
        final var move = action("move", "Move", EntryAction.MOVE, false, readOnly, onAction);
        final var copy = action("copy", "Copy", EntryAction.COPY, false, false, onAction);
        final var delete = action("trash", "Delete", EntryAction.DELETE, true, readOnly, onAction);
        final var trailingSeparator = Ui.divider("fb-bulkbar-sep fb-bulkbar-sep--tight");
        final var clear = Ui.iconButton("cross", "Clear selection", onClear);
        clear.addClassName("fb-bulkbar-clear");
        div.add(counter, leadingSeparator, download, move, copy, delete, trailingSeparator, clear);
    }

    private static Button action(String icon, String label, EntryAction action, boolean danger, boolean disabled,
                                 SerializableConsumer<EntryAction> onAction) {
        final var button = Ui.textButton("fb-bulkbar-btn", label, icon, () -> onAction.accept(action));
        button.getElement().getClassList().set("fb-bulkbar-btn--danger", danger);
        button.setEnabled(!disabled);
        return button;
    }
}
