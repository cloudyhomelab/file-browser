package org.vaadin.filebrowser.ui;

import java.util.List;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.radiobutton.RadioGroupVariant;
import com.vaadin.flow.component.shared.Tooltip;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.function.SerializableConsumer;

/** Pill-shaped single-choice toggle. {@code value} may be null (no option selected). */
public class Segmented<T> extends Composite<RadioButtonGroup<T>> {

    public record Option<T>(T value, String label, String icon, String title) {

        public static <T> Option<T> text(T value, String label) {
            return new Option<>(value, label, null, null);
        }

        public static <T> Option<T> iconOnly(T value, String icon, String title) {
            return new Option<>(value, null, icon, title);
        }
    }

    public Segmented(String className, List<Option<T>> options, T value, SerializableConsumer<T> onChange) {
        final var group = getContent();
        group.addClassNames("fb-seg", className);
        group.addThemeVariants(RadioGroupVariant.AURA_HORIZONTAL);
        final var values = options.stream().map(Option::value).toList();
        group.setItems(values);
        final var renderer = new ComponentRenderer<Span, T>(v -> label(option(options, v)));
        group.setRenderer(renderer);
        group.setValue(value);
        group.addValueChangeListener(e -> onChange.accept(e.getValue()));
    }

    /** Shows the choice without letting it change. */
    public Segmented<T> locked() {
        getContent().setReadOnly(true);
        return this;
    }

    private static <T> Option<T> option(List<Option<T>> options, T value) {
        return options.stream().filter(o -> o.value().equals(value)).findFirst().orElseThrow();
    }

    private static <T> Span label(Option<T> option) {
        final var label = Ui.span("fb-seg-label");
        final var dataValue = String.valueOf(option.value());
        label.getElement().setAttribute("data-value", dataValue);
        if (option.icon() != null) {
            final var icon = new Icon(option.icon());
            // the icon is hidden from screen readers, so the option is named by text they can read
            final var name = Ui.span("fb-sr-only", option.title());
            label.add(icon, name);
            Tooltip.forComponent(label).setText(option.title());
        } else {
            label.setText(option.label());
        }
        return label;
    }
}
