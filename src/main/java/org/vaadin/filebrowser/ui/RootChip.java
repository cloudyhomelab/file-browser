package org.vaadin.filebrowser.ui;

import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.FsRoot;

import com.vaadin.flow.component.html.Span;

/** The tinted square with a disk / server / cloud mark that identifies a file-system kind. */
public class RootChip extends Span {

    public enum Size { XS, S, M, L }

    public RootChip(String kindKey, String icon, Size size) {
        addClassNames("fb-rootchip", "fb-root-" + kindKey, "fb-rootchip--" + size.name().toLowerCase());
        final var mark = new Icon(icon);
        add(mark);
    }

    public static RootChip of(FsRoot root, Catalog catalog, Size size) {
        final var kind = catalog.kind(root);
        return new RootChip(root.kindKey(), kind.icon(), size);
    }
}
