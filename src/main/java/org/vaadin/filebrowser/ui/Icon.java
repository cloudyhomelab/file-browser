package org.vaadin.filebrowser.ui;

import com.vaadin.flow.component.html.Span;

/** A symbol from {@code icons.svg}. Size and colour come from CSS ({@code --icon-size}, {@code color}). */
public class Icon extends Span {

    public Icon(String name) {
        addClassName("fb-icon");
        getElement().setAttribute("aria-hidden", "true");
        getElement().setProperty("innerHTML", "<svg><use href=\"icons.svg#" + name + "\"></use></svg>");
    }
}
