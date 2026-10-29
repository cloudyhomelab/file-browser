package org.vaadin.filebrowser.ui;

import java.util.Locale;

import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.KindInfo;

import com.vaadin.flow.component.html.Span;

/** The tinted square with a disk / server / cloud mark that identifies a file-system kind. */
public class RootChip extends Span {

    public enum Size { XS, S, M, L }

    public RootChip(KindInfo kind, Size size) {
        addClassNames("fb-rootchip", kind.tintClass(), "fb-rootchip--" + size.name().toLowerCase(Locale.ROOT));
        final var mark = new Icon(kind.icon());
        add(mark);
    }

    public static RootChip of(FsRoot root, Catalog catalog, Size size) {
        return new RootChip(catalog.kind(root), size);
    }
}
