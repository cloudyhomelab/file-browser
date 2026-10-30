package org.vaadin.filebrowser.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.function.SerializableRunnable;

@SuppressWarnings("PMD.ShortClassName")
public final class Ui {

    private Ui() {
    }

    public static Div div(String className, Component... children) {
        final var div = new Div(children);
        div.setClassName(className);
        return div;
    }

    public static Span span(String className, String text) {
        final var span = new Span(text);
        span.setClassName(className);
        return span;
    }

    public static Span span(String className, Component... children) {
        final var span = new Span(children);
        span.setClassName(className);
        return span;
    }

    public static Span spacer() {
        return span("fb-spacer", "");
    }

    public static Span divider(String className) {
        return span(className, "");
    }

    /** A plain button whose click does not reach the row or card it sits in. */
    public static NativeButton button(String className, SerializableRunnable onClick, Component... content) {
        final var button = new NativeButton();
        button.setClassName(className);
        button.add(content);
        button.getElement().addEventListener("click", e -> onClick.run()).stopPropagation();
        return button;
    }

    /** A badge led by a dot in its text colour, for a state such as online or read only. */
    public static Badge dotBadge(String text, BadgeVariant... variants) {
        final var dot = span("fb-badge-dot", "");
        final var badge = new Badge(text, dot);
        badge.addThemeVariants(variants);
        return badge;
    }

    /** A checkbox inside a clickable row or tile, which must not also get the click. */
    public static Checkbox checkbox(boolean checked, String ariaLabel, SerializableRunnable onToggle) {
        final var checkbox = new Checkbox(checked);
        checkbox.setAriaLabel(ariaLabel);
        checkbox.addValueChangeListener(e -> onToggle.run());
        checkbox.getElement().addEventListener("click", e -> { }).stopPropagation();
        return checkbox;
    }

    /** A tertiary button whose click does not reach the row, card or bar it sits in. */
    public static Button textButton(String className, String text, String icon, SerializableRunnable onClick) {
        final var iconComponent = new Icon(icon);
        final var button = new Button(text, iconComponent);
        button.addThemeVariants(ButtonVariant.TERTIARY);
        button.addClassName(className);
        button.addClickListener(e -> onClick.run());
        button.getElement().addEventListener("click", e -> { }).stopPropagation();
        return button;
    }

    /**
     * An icon-only button named for screen readers, without a tooltip, for one repeated on every row or tile; the
     * caller adds the click listener. Its click does not reach the row or card it sits in.
     */
    public static Button iconButton(String icon, String label) {
        final var iconComponent = new Icon(icon);
        final var button = new Button(iconComponent);
        button.addThemeVariants(ButtonVariant.TERTIARY);
        button.addClassName("fb-icon-btn");
        button.setAriaLabel(label);
        button.getElement().addEventListener("click", e -> { }).stopPropagation();
        return button;
    }

    public static Button iconButton(String icon, String label, SerializableRunnable onClick) {
        final var button = iconButton(icon, label);
        button.setTooltipText(label);
        button.addClickListener(e -> onClick.run());
        return button;
    }

    public static <T extends Component> T disabled(T component, boolean disabled) {
        component.getElement().setEnabled(!disabled);
        return component;
    }
}
