package org.vaadin.filebrowser.ui.components;

import com.vaadin.flow.component.ClickNotifier;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;

/**
 * A dashed placeholder tile that sits in a grid of cards and starts adding a new one.
 * Styled through the {@code --add-tile-*} custom properties in {@code components/add-tile.css}.
 */
@StyleSheet("components/add-tile.css")
public class AddTile extends Composite<NativeButton> implements ClickNotifier<AddTile> {

    public AddTile(Component icon, String title, String description) {
        final var button = getContent();
        button.addClassName("add-tile");
        final var iconBox = new Span(icon);
        iconBox.addClassName("add-tile-icon");
        final var titleText = new Span(title);
        titleText.addClassName("add-tile-title");
        final var descriptionText = new Span(description);
        descriptionText.addClassName("add-tile-description");
        button.add(iconBox, titleText, descriptionText);
    }
}
