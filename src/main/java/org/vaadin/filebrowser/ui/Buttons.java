package org.vaadin.filebrowser.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.function.SerializableRunnable;

/** Vaadin buttons in the design's five variants. */
public final class Buttons {

    public enum Kind { PRIMARY, SECONDARY, TERTIARY, DANGER, DANGER_FILL }

    private Buttons() {
    }

    public static Button create(Kind kind, String text, String icon, SerializableRunnable onClick) {
        final var button = new Button(text);
        if (icon != null) {
            final var iconComponent = new Icon(icon);
            button.setIcon(iconComponent);
        }
        button.addClassNames("fb-btn", "fb-btn--" + kind.name().toLowerCase().replace('_', '-'));
        switch (kind) {
            case PRIMARY -> button.addThemeVariants(ButtonVariant.PRIMARY);
            case TERTIARY -> button.addThemeVariants(ButtonVariant.TERTIARY);
            case DANGER -> button.addThemeVariants(ButtonVariant.ERROR);
            case DANGER_FILL -> button.addThemeVariants(ButtonVariant.ERROR, ButtonVariant.PRIMARY);
            case SECONDARY -> { }
        }
        if (text == null || text.isEmpty()) {
            button.addClassName("fb-btn--icon");
        }
        button.addClickListener(e -> onClick.run());
        return button;
    }

    public static Button iconOnly(Kind kind, String icon, String title, SerializableRunnable onClick) {
        final var button = create(kind, null, icon, onClick);
        button.setTooltipText(title);
        button.setAriaLabel(title);
        return button;
    }
}
