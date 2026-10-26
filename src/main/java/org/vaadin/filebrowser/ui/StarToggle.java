package org.vaadin.filebrowser.ui;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.function.SerializableRunnable;

public class StarToggle extends Composite<Span> {

    public StarToggle(boolean on, SerializableRunnable onToggle) {
        final var starIcon = new Icon(on ? "star" : "star-outline");
        final var button = Ui.button("fb-star" + (on ? " fb-star--on" : ""), onToggle, starIcon);
        final var title = on ? "Remove from starred" : "Add to starred";
        button.getElement().setAttribute("title", title);
        button.getElement().setAttribute("aria-label", title);
        final var pressed = String.valueOf(on);
        button.getElement().setAttribute("aria-pressed", pressed);
        getContent().add(button);
        getContent().setClassName("fb-star-wrap");
    }
}
