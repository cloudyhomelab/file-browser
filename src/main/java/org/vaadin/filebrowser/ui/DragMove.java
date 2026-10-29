package org.vaadin.filebrowser.ui;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.vaadin.filebrowser.data.Located;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.dnd.DragSource;
import com.vaadin.flow.component.dnd.DropEffect;
import com.vaadin.flow.component.dnd.DropTarget;
import com.vaadin.flow.component.dnd.EffectAllowed;
import com.vaadin.flow.function.SerializableConsumer;

/** Moving entries by dragging them onto a folder. */
public final class DragMove {

    private DragMove() {
    }

    public static void source(Component component, Located located, EntryHandler handler) {
        final var source = DragSource.create(component);
        source.setEffectAllowed(EffectAllowed.MOVE);
        source.setDragData(located);
        source.addDragStartListener(e -> handler.dragStarted());
        source.addDragEndListener(e -> handler.dragEnded());
    }

    /**
     * Folder drop targets, switched on and off together. They start switched off: an active Vaadin drop
     * target takes every drop, including files dragged in from the desktop for upload, so targets are on
     * only while one of our own entries is being dragged.
     */
    public static class Targets implements Serializable {

        private final List<DropTarget<?>> dropTargets = new ArrayList<>();

        public void add(Component component, SerializableConsumer<Located> onDrop) {
            final var target = DropTarget.configure(component, false);
            target.setDropEffect(DropEffect.MOVE);
            target.addDropListener(e -> e.getDragData()
                    .filter(Located.class::isInstance)
                    .map(Located.class::cast)
                    .ifPresent(onDrop));
            dropTargets.add(target);
        }

        public void setActive(boolean on) {
            dropTargets.forEach(t -> t.setActive(on));
        }
    }
}
