package org.vaadin.filebrowser.ui;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.function.SerializableRunnable;

public class EmptyState extends Composite<Div> {

    public enum Kind { FOLDER, SEARCH, STARRED_ROOT, STARRED_ALL }

    public EmptyState(Kind kind, SerializableRunnable onUpload) {
        final var div = getContent();
        div.addClassNames("fb-empty", "fb-empty--" + kind.name().toLowerCase().replace('_', '-'));
        final String icon;
        final String title;
        final String text;
        switch (kind) {
            case SEARCH -> {
                icon = "search";
                title = "No matching files";
                text = "Try a different search term.";
            }
            case STARRED_ROOT -> {
                icon = "star-outline";
                title = "No starred items yet";
                text = "Star a file or folder to pin it here for quick access. Use the star button in a row, the right-click menu, or the details panel.";
            }
            case STARRED_ALL -> {
                icon = "star-outline";
                title = "No starred items yet";
                text = "Open a file system and star a file or folder — it'll be collected here from every connected system.";
            }
            default -> {
                icon = "folder";
                title = "This folder is empty";
                text = "Drag files here or upload to get started.";
            }
        }
        final var iconComponent = new Icon(icon);
        final var iconTile = Ui.span("fb-empty-icon", iconComponent);
        final var titleText = Ui.span("", title);
        final var titleLine = Ui.div("fb-empty-title", titleText);
        final var bodyText = Ui.span("", text);
        final var bodyLine = Ui.div("fb-empty-text", bodyText);
        final var copy = Ui.div("", titleLine, bodyLine);
        div.add(iconTile, copy);
        if (onUpload != null) {
            final var upload = Buttons.create(Buttons.Kind.SECONDARY, "Upload files", "upload", onUpload);
            div.add(upload);
        }
    }
}
