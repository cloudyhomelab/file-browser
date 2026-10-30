package org.vaadin.filebrowser.ui;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.function.SerializableConsumer;
import com.vaadin.flow.function.SerializableRunnable;

/** Compact, icon-only selection actions shown in the toolbar while items are selected. */
public class HeaderBulkActions extends Composite<Div> {

    public HeaderBulkActions(int count, boolean readOnly, SerializableConsumer<EntryAction> onAction, SerializableRunnable onClear) {
        final var div = getContent();
        div.setClassName("fb-header-bulk");
        final var countText = String.valueOf(count);
        final var pill = Ui.span("fb-pill-count", countText);
        final var selectedLabel = Ui.span("", "selected");
        final var counter = Ui.span("fb-header-bulk-count", pill, selectedLabel);
        final var leadingSeparator = Ui.divider("fb-header-bulk-sep");
        final var download = action("download", "Download", EntryAction.DOWNLOAD, false, false, onAction);
        final var move = action("move", "Move", EntryAction.MOVE, false, readOnly, onAction);
        final var copy = action("copy", "Copy", EntryAction.COPY, false, false, onAction);
        final var delete = action("trash", "Delete", EntryAction.DELETE, true, readOnly, onAction);
        final var trailingSeparator = Ui.divider("fb-header-bulk-sep");
        final var clear = Ui.iconButton("cross", "Clear selection", onClear);
        clear.addClassNames("fb-header-bulk-btn", "fb-header-bulk-btn--clear");
        div.add(counter, leadingSeparator, download, move, copy, delete, trailingSeparator, clear);
    }

    private static Button action(String icon, String label, EntryAction action, boolean danger, boolean disabled,
                                 SerializableConsumer<EntryAction> onAction) {
        final var button = Ui.iconButton(icon, label, () -> onAction.accept(action));
        button.addClassName("fb-header-bulk-btn");
        button.getElement().getClassList().set("fb-header-bulk-btn--danger", danger);
        button.setEnabled(!disabled);
        return button;
    }
}
