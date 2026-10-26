package org.vaadin.filebrowser.ui;

import com.vaadin.flow.component.html.Span;

public class Badge extends Span {

    public enum Tone { NEUTRAL, ACCENT, SUCCESS, WARNING, ERROR }

    public Badge(Tone tone, String text, boolean dot) {
        addClassNames("fb-badge", "fb-badge--" + tone.name().toLowerCase());
        if (dot) {
            addClassName("fb-badge--dot");
            final var badgeDot = Ui.span("fb-badge-dot", "");
            add(badgeDot);
        }
        add(text);
    }

    public Badge(Tone tone, String text) {
        this(tone, text, false);
    }
}
