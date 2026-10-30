package org.vaadin.filebrowser.views;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.vaadin.filebrowser.config.FileBrowserProperties;
import org.vaadin.filebrowser.data.Formats;
import org.vaadin.filebrowser.service.PendingDeletes;
import org.vaadin.filebrowser.service.TransferTask;
import org.vaadin.filebrowser.service.UploadTask;
import org.vaadin.filebrowser.service.Workspace;
import org.vaadin.filebrowser.ui.Notices;
import org.vaadin.filebrowser.ui.UploadDock;
import org.vaadin.filebrowser.ui.UploadSender;

import jakarta.annotation.security.PermitAll;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.Layout;
import com.vaadin.flow.router.RouterLayout;
import com.vaadin.flow.server.streams.UploadHandler;
import com.vaadin.flow.shared.Registration;

/**
 * Wraps every signed-in view. It sends uploads, shows their progress and that of moves and copies in the dock, and
 * reports what finished, so all of that keeps going while the user moves between views.
 */
@Layout
@PermitAll
public class MainLayout extends Div implements RouterLayout {

    /** How often the dock is updated while uploads or transfers run. */
    private static final int TASK_POLL_MS = 320;

    private final Workspace workspace;
    private final UploadSender sender;
    private final UploadDock dock;
    private final List<TaskListener> listeners = new CopyOnWriteArrayList<>();
    private Registration pollRegistration;
    /** What the view asks for besides tasks (its auto refresh), in milliseconds; -1 for nothing. */
    private int viewPollMs = -1;

    /** Told after a poll that finished uploads or transfers, so the view can show their results. */
    @FunctionalInterface
    public interface TaskListener extends Serializable {

        /** @param foldersChanged folders were added or moved, which the folder tree shows too */
        void tasksFinished(boolean foldersChanged);
    }

    public MainLayout(Workspace workspace, FileBrowserProperties properties) {
        this.workspace = workspace;
        setClassName("fb-app");
        final var queue = workspace.getUploads();
        final var maxBytes = properties.maxUploadSize().toBytes();
        // runs on the upload request thread, outside the session lock: only touch the thread-safe queue here
        final UploadHandler uploadHandler = event -> {
            final var taskId = event.getRequest().getParameter("task");
            queue.receive(taskId, event.getInputStream(), maxBytes);
        };
        sender = new UploadSender(uploadHandler);
        sender.setOnUploadLost(queue::lost);
        sender.setOnUploadFailed(queue::notSent);
        sender.setWaiting(queue::waitingForBrowser);
        dock = new UploadDock(workspace.getCatalog(), this::clearTasks, this::cancelTransfer);
        add(sender, dock);
    }

    /** The layout around a view, once the view is attached. */
    public static Optional<MainLayout> of(Component view) {
        for (var parent = view.getParent(); parent.isPresent(); parent = parent.orElseThrow().getParent()) {
            if (parent.orElseThrow() instanceof MainLayout layout) {
                return Optional.of(layout);
            }
        }
        return Optional.empty();
    }

    public Registration addTaskListener(TaskListener listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Sends the files of uploads just queued and shows them in the dock. */
    public void upload(List<UploadTask> tasks) {
        sender.upload(tasks);
        tasksChanged();
    }

    /** Uploads or transfers were queued or changed: updates the dock and polls while any run. */
    public void tasksChanged() {
        showDock();
        updatePollInterval();
    }

    /** The view's own polling, e.g. its auto refresh; tasks poll faster while they run. -1 for none. */
    public void setViewPollInterval(int millis) {
        viewPollMs = millis;
        updatePollInterval();
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        pollRegistration = attachEvent.getUI().addPollListener(e -> onPoll());
        tasksChanged();
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (pollRegistration != null) {
            pollRegistration.remove();
            pollRegistration = null;
        }
        detachEvent.getUI().setPollInterval(-1);
        super.onDetach(detachEvent);
    }

    private void clearTasks() {
        workspace.getUploads().clear();
        workspace.getTransfers().clear();
        sender.cancelUploads();
        tasksChanged();
        listeners.forEach(l -> l.tasksFinished(false));
    }

    private void cancelTransfer(TransferTask task) {
        workspace.getTransfers().cancel(task);
        showDock();
    }

    private void showDock() {
        final var uploads = workspace.getUploads();
        dock.show(uploads.loose(), uploads.folders(), workspace.getTransfers().all());
    }

    /** One timer for all: fast while uploads or transfers run, else as the view asks, else off. */
    private void updatePollInterval() {
        final var busy = workspace.getUploads().active() || workspace.getTransfers().pending() || workspace.deletesWaiting();
        final var interval = busy ? TASK_POLL_MS : viewPollMs;
        getUI().ifPresent(ui -> ui.setPollInterval(interval));
    }

    private void onPoll() {
        final var uploads = workspace.getUploads();
        final var ready = uploads.takeReady();
        sender.upload(ready);
        final var finished = uploads.takeFinished();
        final var foldersFinished = uploads.takeFinishedFolders();
        final var transfersFinished = workspace.getTransfers().takeFinished();
        final var deletesFinished = workspace.takeFinishedDeletes();
        // in the order they were queued, so the newest ends up on top of the notifications, as in the dock
        final var announcements = new ArrayList<Map.Entry<Instant, Runnable>>();
        finished.forEach(u -> announcements.add(Map.entry(u.getQueuedAt(), () -> announce(u))));
        foldersFinished.forEach(f -> announcements.add(Map.entry(f.getQueuedAt(),
                () -> Notices.seen(f.outcome(), f.firstError() == null ? "check" : "warn"))));
        transfersFinished.forEach(t -> announcements.add(Map.entry(t.getQueuedAt(), () -> announce(t))));
        announcements.sort(Map.Entry.comparingByKey());
        announcements.forEach(a -> a.getValue().run());
        deletesFinished.forEach(MainLayout::announce);
        showDock();
        updatePollInterval();
        // transfers and folder uploads add folders, which the folder tree shows too
        final var foldersChanged = !transfersFinished.isEmpty() || !ready.isEmpty() || !foldersFinished.isEmpty()
                || !deletesFinished.isEmpty();
        if (foldersChanged || !finished.isEmpty()) {
            listeners.forEach(l -> l.tasksFinished(foldersChanged));
        }
    }

    private static void announce(UploadTask upload) {
        switch (upload.getState()) {
            case DONE -> Notices.seen(upload.getSummary() != null
                    ? "“" + upload.getName() + "” in " + upload.destinationLabel() + ": " + upload.getSummary()
                    : upload.isReplace() ? "Replaced “" + upload.getName() + "” in " + upload.destinationLabel()
                    : "Uploaded “" + upload.getName() + "” to " + upload.destinationLabel(), "check");
            case FAILED -> Notices.seen(upload.getError(), "warn");
            default -> { }
        }
    }

    private static void announce(PendingDeletes.Outcome delete) {
        final var pending = delete.pending();
        if (delete.deleted() > 0) {
            final var all = delete.deleted() == pending.entries().size();
            Notices.show((all ? pending.label() : Formats.plural(delete.deleted(), "item")) + " deleted", "trash");
        }
        delete.kept().forEach(name -> Notices.show("“" + name + "” changed during the wait, so it was kept.", "warn"));
        if (delete.problem() != null) {
            Notices.error(delete.problem());
        }
    }

    private static void announce(TransferTask transfer) {
        switch (transfer.getState()) {
            case DONE -> Notices.seen(transfer.getMessage(), transfer.getKind() == TransferTask.Kind.MOVE ? "move" : "copy");
            case FAILED -> Notices.seen(transfer.getMessage(), "warn");
            case CANCELLED -> Notices.seen(transfer.getMessage(), "cross");
            default -> { }
        }
    }
}
