package org.vaadin.filebrowser.ui;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.function.SerializableRunnable;

public class StarToggle extends Composite<Span> {

    public StarToggle(boolean on, SerializableRunnable onToggle) {
        final var title = on ? "Remove from starred" : "Add to starred";
        final var button = Ui.iconButton(on ? "star" : "star-outline", title, onToggle);
        button.addClassName("fb-star");
        button.getElement().getClassList().set("fb-star--on", on);
        final var pressed = String.valueOf(on);
        button.getElement().setAttribute("aria-pressed", pressed);
        getContent().add(button);
        getContent().setClassName("fb-star-wrap");
    }
}
