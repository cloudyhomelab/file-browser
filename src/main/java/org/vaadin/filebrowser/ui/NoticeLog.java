package org.vaadin.filebrowser.ui;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.vaadin.flow.function.SerializableRunnable;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.shared.Registration;

/**
 * The notifications of one sign-in session, newest first, shared by all its tabs. Only touched under the session
 * lock, so listeners of other tabs may update their components directly; those tabs show it on their next request.
 */
public final class NoticeLog implements Serializable {

    /** Oldest notifications are dropped beyond this, so a long session cannot grow the list without bound. */
    static final int MAX_NOTICES = 100;

    private final List<Notice> entries = new ArrayList<>();
    private final List<SerializableRunnable> listeners = new ArrayList<>();
    private long nextId;

    public static NoticeLog current() {
        final var session = VaadinSession.getCurrent();
        final var existing = session.getAttribute(NoticeLog.class);
        if (existing != null) {
            return existing;
        }
        final var created = new NoticeLog();
        session.setAttribute(NoticeLog.class, created);
        return created;
    }

    public void add(String message, String icon, boolean read) {
        final var id = nextId;
        nextId++;
        final var notice = new Notice(id, message, icon, Instant.now(), read);
        entries.addFirst(notice);
        if (entries.size() > MAX_NOTICES) {
            entries.removeLast();
        }
        fire();
    }

    public List<Notice> notices() {
        return List.copyOf(entries);
    }

    public int unreadCount() {
        return (int) entries.stream().filter(n -> !n.read()).count();
    }

    public void markAllRead() {
        if (unreadCount() == 0) {
            return;
        }
        entries.replaceAll(Notice::asRead);
        fire();
    }

    public void remove(long id) {
        if (entries.removeIf(n -> n.id() == id)) {
            fire();
        }
    }

    public Registration addListener(SerializableRunnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void fire() {
        List.copyOf(listeners).forEach(Runnable::run);
    }

    public record Notice(long id, String message, String icon, Instant time, boolean read) implements Serializable {

        Notice asRead() {
            return new Notice(id, message, icon, time, true);
        }
    }
}
