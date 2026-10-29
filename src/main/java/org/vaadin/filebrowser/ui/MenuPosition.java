package org.vaadin.filebrowser.ui;

import java.io.Serializable;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.dom.DomEvent;
import com.vaadin.flow.function.SerializableConsumer;

/** Where a pointer event happened, plus the viewport size so menus can stay on screen. */
public record MenuPosition(double clientX, double clientY, double viewportWidth, double viewportHeight) implements Serializable {

    private static final String CLIENT_X = "event.clientX";
    private static final String CLIENT_Y = "event.clientY";
    private static final String WIDTH = "window.innerWidth";
    private static final String HEIGHT = "window.innerHeight";

    static MenuPosition of(DomEvent event) {
        final var data = event.getEventData();
        return new MenuPosition(data.get(CLIENT_X).asDouble(), data.get(CLIENT_Y).asDouble(),
                data.get(WIDTH).asDouble(), data.get(HEIGHT).asDouble());
    }

    /** Right-click on the component opens a menu instead of the browser's. */
    public static void onContextMenu(Component component, SerializableConsumer<MenuPosition> handler) {
        listen(component, "contextmenu", handler).preventDefault();
    }

    /** Click handler that does not propagate to the enclosing row or card. */
    public static void onClick(Component component, SerializableConsumer<MenuPosition> handler) {
        listen(component, "click", handler).stopPropagation();
    }

    private static com.vaadin.flow.dom.DomListenerRegistration listen(Component component, String type,
                                                                    SerializableConsumer<MenuPosition> handler) {
        return component.getElement().addEventListener(type, e -> handler.accept(of(e)))
                .addEventData(CLIENT_X).addEventData(CLIENT_Y).addEventData(WIDTH).addEventData(HEIGHT);
    }
}
