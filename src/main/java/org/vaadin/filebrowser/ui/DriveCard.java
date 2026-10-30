package org.vaadin.filebrowser.ui;

import org.vaadin.filebrowser.data.Access;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.KindInfo;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.function.SerializableConsumer;
import com.vaadin.flow.function.SerializableRunnable;

public class DriveCard extends Composite<Card> {

    /** {@code onMenu} opens the admin menu from the card's "⋯" button; null hides the button. */
    public DriveCard(FsRoot root, KindInfo kind, SerializableRunnable onOpen, SerializableRunnable onRequestAccess,
                     SerializableConsumer<Component> onMenu) {
        final var card = getContent();
        card.addClassNames("fb-drive", kind.tintClass());
        final var disabled = !root.browsable();
        if (disabled) {
            card.addClassName("fb-drive--disabled");
        } else {
            card.setAriaRole("button");
            card.getElement().setAttribute("tabindex", "0");
            card.getElement().addEventListener("click", e -> onOpen.run());
            // only the card itself: Enter on the "⋯" button must not open it too
            card.getElement().addEventListener("keydown", e -> onOpen.run())
                    .setFilter("event.key === 'Enter' && event.target === element");
        }

        final var chip = new RootChip(kind, RootChip.Size.L);
        card.setHeaderPrefix(chip);
        card.setTitle(root.name());
        card.setTitleHeadingLevel(3);
        final var endpointText = Ui.span("", root.endpoint());
        final var endpoint = Ui.div("fb-drive-endpoint", endpointText);
        card.setSubtitle(endpoint);

        final var status = Ui.span("fb-drive-status" + (root.online() ? "" : " fb-drive-status--off"), "");
        status.getElement().setAttribute("title", root.online() ? "Online" : "Offline");
        final var suffix = Ui.div("fb-drive-suffix", status);
        if (onMenu != null) {
            final var dots = Ui.iconButton("dots", "File system actions");
            dots.addClassNames("fb-icon-btn--s", "fb-drive-more");
            dots.addClickListener(e -> onMenu.accept(dots));
            suffix.add(dots);
        }
        card.setHeaderSuffix(suffix);

        final var rule = Ui.span("fb-drive-rule", "");
        final var capacity = new CapacityMeter(root, kind, CapacityMeter.Style.CARD);
        card.add(rule, capacity);

        final var typeBadge = new Badge(kind.label());
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
        card.addToFooter(foot);

        if (root.locked()) {
            final var request = Ui.textButton("fb-drive-request", "Request access", "lock", onRequestAccess);
            final var lock = Ui.div("fb-drive-lock", request);
            card.add(lock);
        }
    }

    private static Component accessBadge(FsRoot root) {
        if (!root.online()) {
            return Ui.dotBadge("Offline");
        }
        if (root.access() == Access.READ_WRITE) {
            return Ui.dotBadge("Read & write", BadgeVariant.SUCCESS);
        }
        if (root.access() == Access.READ_ONLY) {
            return Ui.dotBadge("Read only", BadgeVariant.WARNING);
        }
        return Ui.dotBadge("No access", BadgeVariant.ERROR);
    }
}
