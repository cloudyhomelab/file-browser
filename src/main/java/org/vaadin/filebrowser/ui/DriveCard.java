package org.vaadin.filebrowser.ui;

import org.vaadin.filebrowser.data.Access;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.KindInfo;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.function.SerializableConsumer;
import com.vaadin.flow.function.SerializableRunnable;

public class DriveCard extends Composite<Div> {

    /** {@code onMenu} opens the admin menu from the card's "⋯" button; null hides the button. */
    public DriveCard(FsRoot root, KindInfo kind, SerializableRunnable onOpen, SerializableRunnable onRequestAccess,
                     SerializableConsumer<MenuPosition> onMenu) {
        final var card = getContent();
        card.addClassNames("fb-drive", kind.tintClass());
        final var disabled = !root.browsable();
        if (disabled) {
            card.addClassName("fb-drive--disabled");
        } else {
            card.getElement().setAttribute("role", "button");
            card.getElement().setAttribute("tabindex", "0");
            card.addClickListener(e -> onOpen.run());
            // only the card itself: Enter on the "⋯" button must not open it too
            card.getElement().addEventListener("keydown", e -> onOpen.run())
                    .setFilter("event.key === 'Enter' && event.target === element");
        }

        final var status = Ui.span("fb-drive-status" + (root.online() ? "" : " fb-drive-status--off"), "");
        status.getElement().setAttribute("title", root.online() ? "Online" : "Offline");
        final var chip = new RootChip(kind, RootChip.Size.L);
        final var title = new H3(root.name());
        final var endpointText = Ui.span("", root.endpoint());
        final var endpoint = Ui.div("fb-drive-endpoint", endpointText);
        final var titles = Ui.div("fb-drive-titles", title, endpoint);
        final var head = Ui.div("fb-drive-head", chip, titles, status);
        if (onMenu != null) {
            final var dotsIcon = new Icon("dots");
            final var dots = Ui.button("fb-icon-btn fb-icon-btn--s fb-drive-more", () -> { }, dotsIcon);
            dots.getElement().setAttribute("aria-label", "File system actions");
            MenuPosition.onClick(dots, onMenu);
            head.add(dots);
        }

        final var typeBadge = new Badge(Badge.Tone.NEUTRAL, kind.label());
        final var detail = root.status().detail();
        final var foot = Ui.div("fb-drive-foot", typeBadge);
        if (!detail.isBlank()) {
            final var region = Ui.span("fb-drive-region", detail);
            region.getElement().setAttribute("title", detail);
            foot.add(region);
        }
        final var spacer = Ui.spacer();
        final var access = accessBadge(root);
        foot.add(spacer, access);

        final var rule = Ui.span("fb-drive-rule", "");
        final var capacity = new CapacityMeter(root, kind, CapacityMeter.Style.CARD);
        final var separator = Ui.divider("fb-drive-sep");
        card.add(rule, head, capacity, separator, foot);

        if (root.locked()) {
            final var lockIcon = new Icon("lock");
            final var requestLabel = Ui.span("", "Request access");
            final var request = Ui.button("fb-drive-request", onRequestAccess, lockIcon, requestLabel);
            final var lock = Ui.div("fb-drive-lock", request);
            card.add(lock);
        }
    }

    private static Component accessBadge(FsRoot root) {
        if (!root.online()) {
            return new Badge(Badge.Tone.NEUTRAL, "Offline", true);
        }
        if (root.access() == Access.READ_WRITE) {
            return new Badge(Badge.Tone.SUCCESS, "Read & write", true);
        }
        if (root.access() == Access.READ_ONLY) {
            return new Badge(Badge.Tone.WARNING, "Read only", true);
        }
        return new Badge(Badge.Tone.ERROR, "No access", true);
    }
}
