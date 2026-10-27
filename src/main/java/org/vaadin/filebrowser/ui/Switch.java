package org.vaadin.filebrowser.ui;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.function.SerializableConsumer;

/** An on/off switch with its label. */
public class Switch extends Composite<NativeButton> {

    private final SerializableConsumer<Boolean> onChange;
    private boolean on;

    public Switch(String label, boolean on, SerializableConsumer<Boolean> onChange) {
        this.onChange = onChange;
        final var button = getContent();
        button.addClassName("fb-switch");
        button.getElement().setAttribute("role", "switch");
        button.getElement().setAttribute("aria-label", label);
        final var track = Ui.span("fb-switch-track", Ui.span("fb-switch-thumb"));
        final var text = Ui.span("fb-switch-label", label);
        button.add(track, text);
        button.addClickListener(e -> setOn(!this.on, true));
        setOn(on, false);
    }

    public boolean isOn() {
        return on;
    }

    private void setOn(boolean on, boolean fromUser) {
        this.on = on;
        final var checked = String.valueOf(on);
        getContent().getElement().setAttribute("aria-checked", checked);
        getContent().getElement().getClassList().set("fb-switch--on", on);
        if (fromUser) {
            onChange.accept(on);
        }
    }
}
