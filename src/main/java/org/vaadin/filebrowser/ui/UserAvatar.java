package org.vaadin.filebrowser.ui;

import org.vaadin.filebrowser.data.Formats;

import com.vaadin.flow.component.html.Span;

/** Initials on a colour picked from a stable hash of the name, as in the design. */
public class UserAvatar extends Span {

    private static final int PALETTE_SIZE = 8;
    private static final int HASH_MULTIPLIER = 31;

    public enum Size { XS, S, M, L, XL }

    public UserAvatar(String name, Size size) {
        final var colorClass = "fb-avatar-c" + colorIndex(name);
        addClassNames("fb-avatar", "fb-avatar--" + size.name().toLowerCase(), colorClass);
        final var initials = Formats.initials(name);
        setText(initials);
        getElement().setAttribute("title", name == null ? "" : name);
    }

    private static int colorIndex(String name) {
        long h = 0;
        for (final var c : (name == null ? "" : name).toCharArray()) {
            h = (h * HASH_MULTIPLIER + c) & 0xffffffffL;
        }
        return (int) (h % PALETTE_SIZE);
    }
}
