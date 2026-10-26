package org.vaadin.filebrowser.ui;

import com.vaadin.flow.component.notification.Notification;

public final class Toasts {

    private static final int DURATION_MS = 2600;

    private Toasts() {
    }

    public static void show(String message, String icon) {
        final var notification = new Notification();
        notification.addClassName("fb-toast");
        final var iconComponent = new Icon(icon);
        final var iconBadge = Ui.span("fb-toast-icon", iconComponent);
        final var text = Ui.span("fb-toast-text", message);
        notification.add(iconBadge, text);
        notification.setPosition(Notification.Position.BOTTOM_CENTER);
        notification.setDuration(DURATION_MS);
        notification.open();
    }

    public static void readOnly() {
        show("This file system is read only", "warn");
    }
}
