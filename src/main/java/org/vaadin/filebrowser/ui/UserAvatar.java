package org.vaadin.filebrowser.ui;

import java.util.Locale;

import org.vaadin.filebrowser.data.Formats;

import com.vaadin.flow.component.avatar.Avatar;
import com.vaadin.flow.component.avatar.AvatarVariant;

/** Initials on a colour picked from a stable hash of the name, as in the design. */
public class UserAvatar extends Avatar {

    private static final int PALETTE_SIZE = 8;
    private static final int HASH_MULTIPLIER = 31;

    // the constants name CSS modifier classes, e.g. fb-avatar--m
    @SuppressWarnings("PMD.ShortVariable")
    public enum Size { XS, S, M, L, XL }

    public UserAvatar(String name, Size size) {
        super(name);
        addClassName("fb-avatar--" + size.name().toLowerCase(Locale.ROOT));
        addThemeVariants(AvatarVariant.AURA_FILLED);
        // Avatar would show a person icon for a missing name; the design shows "?"
        final var initials = Formats.initials(name);
        setAbbreviation(initials);
        setColorIndex(colorIndex(name));
        setTooltipEnabled(name != null);
    }

    /** The palette slot for {@code name}; the same name always gets the same colour. */
    public static int colorIndex(String name) {
        long hash = 0;
        for (final var c : (name == null ? "" : name).toCharArray()) {
            hash = (hash * HASH_MULTIPLIER + c) & 0xffffffffL;
        }
        return (int) (hash % PALETTE_SIZE);
    }
}
