package org.vaadin.filebrowser.ui;

import com.vaadin.flow.component.Component;
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

    /** A checkbox inside a clickable row or tile, which must not also get the click. */
    public static Checkbox checkbox(boolean checked, String ariaLabel, SerializableRunnable onToggle) {
        final var checkbox = new Checkbox(checked);
        checkbox.setAriaLabel(ariaLabel);
        checkbox.addValueChangeListener(e -> onToggle.run());
        checkbox.getElement().addEventListener("click", e -> { }).stopPropagation();
        return checkbox;
    }

    public static NativeButton iconButton(String icon, String title, SerializableRunnable onClick) {
        final var iconComponent = new Icon(icon);
        final var button = button("fb-icon-btn", onClick, iconComponent);
        button.getElement().setAttribute("title", title);
        button.getElement().setAttribute("aria-label", title);
        return button;
    }

    public static NativeButton withTitle(NativeButton button, String title) {
        button.getElement().setAttribute("title", title);
        return button;
    }

    public static <T extends Component> T disabled(T component, boolean disabled) {
        component.getElement().setEnabled(!disabled);
        return component;
    }
}
