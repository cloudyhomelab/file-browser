package org.vaadin.filebrowser.ui.components;

import com.vaadin.flow.component.ClickNotifier;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;

/**
 * A tile for one item in a grid: media above a title and subtitle, with optional corner content that
 * appears on hover. Styled through the {@code --item-tile-*} custom properties in
 * {@code components/item-tile.css}.
 */
@StyleSheet("components/item-tile.css")
public class ItemTile extends Composite<Div> implements ClickNotifier<ItemTile> {

    private Span startControl;
    private Span startIndicator;
    private Span endControls;

    public ItemTile(Component media, String title, String subtitle) {
        final var tile = getContent();
        tile.addClassName("item-tile");
        final var mediaBox = wrap("item-tile-media", media);
        final var titleText = new Span(title);
        titleText.addClassName("item-tile-title");
        final var subtitleText = new Span(subtitle);
        subtitleText.addClassName("item-tile-subtitle");
        final var text = new Div(titleText, subtitleText);
        text.addClassName("item-tile-text");
        tile.add(mediaBox, text);
    }

    public void setSelected(boolean selected) {
        getContent().getElement().getClassList().set("item-tile--selected", selected);
    }

    /** Top-left control, e.g. a checkbox; shown on hover and while selected. */
    public void setStartControl(Component control) {
        startControl = replace(startControl, wrap("item-tile-start", control));
    }

    /** Top-left mark shown at rest; the start control takes its place on hover and while selected. */
    public void setStartIndicator(Component indicator) {
        startIndicator = replace(startIndicator, wrap("item-tile-indicator", indicator));
    }

    /** Top-right controls, e.g. action buttons; shown on hover. */
    public void setEndControls(Component... controls) {
        endControls = replace(endControls, wrap("item-tile-end", controls));
    }

    private Span replace(Span old, Span replacement) {
        if (old != null) {
            getContent().remove(old);
        }
        getContent().add(replacement);
        return replacement;
    }

    private static Span wrap(String className, Component... content) {
        final var span = new Span(content);
        span.addClassName(className);
        return span;
    }
}
