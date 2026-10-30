package org.vaadin.filebrowser.ui;

import java.time.Duration;

import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.function.SerializableRunnable;

/** Posts to the session's {@link NoticeLog}. Errors and refusals also pop up, since nothing else shows them. */
public final class Notices {

    private static final int TOAST_DURATION_MS = 2600;
    private static final String ERROR_ICON = "warn";

    private Notices() {
    }

    public static void show(String message, String icon) {
        NoticeLog.current().add(message, icon, false);
    }

    /** An outcome another panel already showed, such as the upload dock. */
    public static void seen(String message, String icon) {
        NoticeLog.current().add(message, icon, true);
    }

    public static void error(String message) {
        NoticeLog.current().add(message, ERROR_ICON, true);
        toast(message);
    }

    /** A toast with an Undo button that closes after {@code duration}; the outcome itself goes to the log later. */
    public static void undoable(String message, String icon, Duration duration, SerializableRunnable onUndo) {
        final var notification = new Notification();
        notification.addClassName("fb-toast");
        final var iconComponent = new Icon(icon);
        final var iconBadge = Ui.span("fb-toast-icon", iconComponent);
        final var text = Ui.span("fb-toast-text", message);
        final var undo = Buttons.create(Buttons.Kind.TERTIARY, "Undo", null, () -> {
            notification.close();
            onUndo.run();
        });
        undo.addClassName("fb-toast-action");
        notification.add(iconBadge, text, undo);
        notification.setPosition(Notification.Position.BOTTOM_CENTER);
        notification.setDuration((int) duration.toMillis());
        notification.open();
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
