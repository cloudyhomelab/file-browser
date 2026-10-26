package org.vaadin.filebrowser.ui;

import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.function.SerializableRunnable;

/** The design's compact checkbox. Clicks do not reach the row it sits in. */
public class CheckMark extends Span {

    public CheckMark(boolean checked, boolean indeterminate, SerializableRunnable onToggle) {
        addClassName("fb-check");
        if (checked) {
            addClassName("fb-check--on");
            final var checkIcon = new Icon("check");
            add(checkIcon);
        } else if (indeterminate) {
            addClassName("fb-check--on");
            final var dash = Ui.span("fb-check-dash", "");
            add(dash);
        }
        getElement().setAttribute("role", "checkbox");
        getElement().setAttribute("tabindex", "0");
        final var ariaChecked = indeterminate && !checked ? "mixed" : String.valueOf(checked);
        getElement().setAttribute("aria-checked", ariaChecked);
        getElement().addEventListener("click", e -> onToggle.run()).stopPropagation();
    }
}
