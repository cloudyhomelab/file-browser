package org.vaadin.filebrowser.ui;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.popover.Popover;
import com.vaadin.flow.component.popover.PopoverPosition;
import com.vaadin.flow.component.popover.PopoverVariant;
import com.vaadin.flow.shared.Registration;

/** Bell with an unread badge; opens the session's notifications. Everything shown is read once the list closes. */
public class NotificationBell extends Composite<Div> {

    /** Badge counts above this show as "99+". */
    private static final int MAX_BADGE_COUNT = 99;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final NativeButton trigger = new NativeButton();
    private final Popover popover = new Popover();
    private final Div list = Ui.div("fb-notice-list");
    private Registration logRegistration;

    public NotificationBell(PopoverPosition position) {
        trigger.setClassName("fb-bell");
        trigger.getElement().setAttribute("aria-label", "Notifications");

        popover.setTarget(trigger);
        popover.setPosition(position);
        popover.addThemeVariants(PopoverVariant.NO_PADDING);
        popover.addOpenedChangeListener(e -> {
            trigger.getElement().getClassList().set("fb-open", e.isOpened());
            if (!e.isOpened()) {
                NoticeLog.current().markAllRead();
            }
        });
        final var titleText = Ui.span("", "Notifications");
        final var title = Ui.div("fb-notice-title", titleText);
        final var panel = Ui.div("fb-menu fb-notice-panel", title, list);
        popover.add(panel);

        getContent().setClassName("fb-bell-wrap");
        getContent().add(trigger, popover);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        logRegistration = NoticeLog.current().addListener(this::render);
        render();
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        logRegistration.remove();
        logRegistration = null;
        super.onDetach(detachEvent);
    }

    private void render() {
        final var log = NoticeLog.current();
        trigger.removeAll();
        final var bellIcon = new Icon("bell");
        trigger.add(bellIcon);
        final var unread = log.unreadCount();
        if (unread > 0) {
            final var badgeText = unread > MAX_BADGE_COUNT ? MAX_BADGE_COUNT + "+" : String.valueOf(unread);
            final var badge = Ui.span("fb-bell-badge", badgeText);
            trigger.add(badge);
        }

        list.removeAll();
        final var notices = log.notices();
        if (notices.isEmpty()) {
            final var emptyText = Ui.span("", "No notifications");
            final var empty = Ui.div("fb-notice-empty", emptyText);
            list.add(empty);
            return;
        }
        final var zone = ZoneId.systemDefault();
        for (final var notice : notices) {
            final var icon = new Icon(notice.icon());
            final var iconBadge = Ui.span("fb-notice-icon", icon);
            final var message = Ui.span("fb-notice-message", notice.message());
            final var timeText = TIME.format(notice.time().atZone(zone));
            final var time = Ui.span("fb-notice-time", timeText);
            final var text = Ui.span("fb-notice-text", message, time);
            final var remove = Ui.iconButton("cross", "Remove", () -> log.remove(notice.id()));
            remove.addClassName("fb-icon-btn--s");
            final var row = Ui.div("fb-notice" + (notice.read() ? "" : " fb-notice--unread"), iconBadge, text, remove);
            list.add(row);
        }
    }
}
