package org.vaadin.filebrowser.ui;

import org.vaadin.filebrowser.data.RootType;

import com.vaadin.flow.component.html.Span;

/** The tinted square with a disk / server / cloud mark that identifies a file-system type. */
public class RootChip extends Span {

    public enum Size { XS, S, M, L }

    public RootChip(RootType type, Size size) {
        addClassNames("fb-rootchip", "fb-root-" + type.key(), "fb-rootchip--" + size.name().toLowerCase());
        final var mark = new Icon("t-" + type.key());
        add(mark);
    }
}
