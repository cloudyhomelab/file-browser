package org.vaadin.filebrowser.ui;

import java.util.List;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.function.SerializableConsumer;

/** Pill-shaped single-choice toggle. {@code value} may be null (no option selected). */
public class Segmented<T> extends Composite<Div> {

    public record Option<T>(T value, String label, String icon, String title) {

        public static <T> Option<T> text(T value, String label) {
            return new Option<>(value, label, null, null);
        }

        public static <T> Option<T> iconOnly(T value, String icon, String title) {
            return new Option<>(value, null, icon, title);
        }
    }

    public Segmented(String className, List<Option<T>> options, T value, SerializableConsumer<T> onChange) {
        getContent().addClassNames("fb-seg", className);
        getContent().getElement().setAttribute("role", "radiogroup");
        for (final var option : options) {
            final var on = option.value().equals(value);
            final var button = Ui.button("fb-seg-btn" + (on ? " fb-seg-btn--on" : ""), () -> onChange.accept(option.value()));
            if (option.icon() != null) {
                final var icon = new Icon(option.icon());
                button.add(icon);
            }
            if (option.label() != null) {
                button.setText(option.label());
            }
            if (option.title() != null) {
                button.getElement().setAttribute("title", option.title());
                button.getElement().setAttribute("aria-label", option.title());
            }
            button.getElement().setAttribute("role", "radio");
            final var checked = String.valueOf(on);
            button.getElement().setAttribute("aria-checked", checked);
            final var dataValue = String.valueOf(option.value());
            button.getElement().setAttribute("data-value", dataValue);
            getContent().add(button);
        }
    }

    /** Shows the choice without letting it change. */
    public Segmented<T> locked() {
        getContent().addClassName("fb-seg--locked");
        getContent().getChildren().forEach(button -> button.getElement().setAttribute("disabled", true));
        return this;
    }
}
