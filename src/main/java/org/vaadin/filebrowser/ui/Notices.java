package org.vaadin.filebrowser.ui;

import com.vaadin.flow.component.notification.Notification;

/** Posts to the session's {@link NoticeLog}. Errors and refusals also pop up, since nothing else shows them. */
public final class Notices {

    private static final int TOAST_DURATION_MS = 2600;
    private static final String ERROR_ICON = "warn";

    private Notices() {
    }

    public static void show(String message, String icon) {
        NoticeLog.current().add(message, icon, false);
    }

    public static void error(String message) {
        NoticeLog.current().add(message, ERROR_ICON, true);
        toast(message);
    }

    public static void readOnly() {
        error("This file system is read only");
    }

    private static void toast(String message) {
        final var notification = new Notification();
        notification.addClassName("fb-toast");
        final var iconComponent = new Icon(ERROR_ICON);
        final var iconBadge = Ui.span("fb-toast-icon", iconComponent);
        final var text = Ui.span("fb-toast-text", message);
        notification.add(iconBadge, text);
        notification.setPosition(Notification.Position.BOTTOM_CENTER);
        notification.setDuration(TOAST_DURATION_MS);
        notification.open();
    }
}
