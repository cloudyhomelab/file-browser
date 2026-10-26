package org.vaadin.filebrowser.ui;

import org.vaadin.filebrowser.data.Access;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.KindInfo;
import org.vaadin.filebrowser.storage.MountInfo;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.function.SerializableRunnable;

public class DriveCard extends Composite<Div> {

    public DriveCard(FsRoot root, KindInfo kind, SerializableRunnable onOpen, SerializableRunnable onRequestAccess) {
        final var card = getContent();
        card.addClassNames("fb-drive", "fb-root-" + root.kindKey());
        final var disabled = !root.browsable();
        if (disabled) {
            card.addClassName("fb-drive--disabled");
        } else {
            card.getElement().setAttribute("role", "button");
            card.getElement().setAttribute("tabindex", "0");
            card.addClickListener(e -> onOpen.run());
            card.getElement().addEventListener("keydown", e -> onOpen.run()).setFilter("event.key === 'Enter'");
        }

        final var status = Ui.span("fb-drive-status" + (root.online() ? "" : " fb-drive-status--off"), "");
        status.getElement().setAttribute("title", root.online() ? "Online" : "Offline");
        final var chip = new RootChip(root.kindKey(), kind.icon(), RootChip.Size.L);
        final var title = new H3(root.name());
        final var endpointText = Ui.span("", root.endpoint());
        final var endpoint = Ui.div("fb-drive-endpoint", endpointText);
        final var titles = Ui.div("fb-drive-titles", title, endpoint);
        final var head = Ui.div("fb-drive-head", chip, titles, status);

        final var typeBadge = new Badge(Badge.Tone.NEUTRAL, kind.label());
        final var network = root.mount().kind() == MountInfo.Kind.NFS || root.mount().kind() == MountInfo.Kind.SMB;
        final var detail = network ? root.mount().source() : root.region();
        final var region = Ui.span("fb-drive-region", detail);
        region.getElement().setAttribute("title", detail);
        final var spacer = Ui.spacer();
        final var access = accessBadge(root);
        final var foot = Ui.div("fb-drive-foot", typeBadge, region, spacer, access);

        final var rule = Ui.span("fb-drive-rule", "");
        final var capacity = new CapacityMeter(root, CapacityMeter.Style.CARD);
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
