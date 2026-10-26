package org.vaadin.filebrowser.ui;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.Formats;
import org.vaadin.filebrowser.data.FsEntry;
import org.vaadin.filebrowser.data.FsRoot;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.html.Aside;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.function.SerializableConsumer;
import com.vaadin.flow.function.SerializableRunnable;

public class DetailsPanel extends Composite<Aside> {

    public DetailsPanel(FsEntry entry, FsRoot root, List<String> path, boolean readOnly, boolean starred, Catalog catalog,
                        LocalDate today, SerializableRunnable onClose, SerializableConsumer<EntryAction> onAction) {
        final var aside = getContent();
        aside.setClassName("fb-details");

        final var title = Ui.span("fb-details-title", "Details");
        final var close = Ui.iconButton("cross", "Close details", onClose);
        final var head = Ui.div("fb-details-head", title, close);
        aside.add(head);

        final var image = "image".equals(entry.type());
        final var preview = Ui.div("fb-details-preview" + (image ? " fb-details-preview--image fb-type-image" : ""));
        final Component previewContent = image ? new Icon("photo") : new FileTile(entry, catalog);
        preview.add(previewContent);

        final var kind = entry.folder() ? "Folder" : entry.type().toUpperCase(Locale.ROOT);
        final var kindBadge = new Badge(Badge.Tone.ACCENT, kind);
        final var badges = Ui.div("fb-details-badges", kindBadge);
        if (starred) {
            final var starBadge = new Badge(Badge.Tone.ACCENT, "Starred");
            final var starIcon = new Icon("star");
            starBadge.addComponentAsFirst(starIcon);
            badges.add(starBadge);
        }
        if (readOnly) {
            final var readOnlyBadge = new Badge(Badge.Tone.WARNING, "Read only");
            badges.add(readOnlyBadge);
        }

        final var location = new ArrayList<String>();
        location.add(root.name());
        location.addAll(path);
        location.add(entry.name());
        final var kindValue = new Span(entry.folder() ? "Folder" : kind + " file");
        final var sizeText = entry.folder() ? entry.children().size() + " items" : Formats.size(entry.size());
        final var sizeValue = new Span(sizeText);
        final var modifiedText = Formats.daysAgo(entry.modifiedDaysAgo(), today);
        final var modifiedValue = new Span(modifiedText);
        final var ownerAvatar = new UserAvatar(entry.owner(), UserAvatar.Size.XS);
        final var ownerName = new Span(entry.owner());
        final var ownerValue = Ui.span("fb-details-owner", ownerAvatar, ownerName);
        final var locationText = "/" + String.join("/", location);
        final var locationValue = new Span(locationText);
        final var kindRow = metaRow("Kind", kindValue, "");
        final var sizeRow = metaRow("Size", sizeValue, "");
        final var modifiedRow = metaRow("Modified", modifiedValue, "");
        final var ownerRow = metaRow("Owner", ownerValue, "");
        final var locationRow = metaRow("Location", locationValue, "fb-details-meta-value--path");
        final var meta = Ui.div("fb-details-meta", kindRow, sizeRow, modifiedRow, ownerRow, locationRow);

        final var name = new H3(entry.name());
        final var heading = Ui.div("", name, badges);
        final var body = Ui.div("fb-details-body", preview, heading, meta);
        aside.add(body);

        final var download = Buttons.create(Buttons.Kind.PRIMARY, "Download", "download", () -> onAction.accept(EntryAction.DOWNLOAD));
        download.addClassName("fb-grow");
        final var star = Buttons.iconOnly(Buttons.Kind.SECONDARY, starred ? "star" : "star-outline",
                starred ? "Remove from starred" : "Add to starred", () -> onAction.accept(EntryAction.STAR));
        star.getElement().getClassList().set("fb-btn--starred", starred);
        final var rename = Buttons.create(Buttons.Kind.SECONDARY, "Rename", "edit", () -> onAction.accept(EntryAction.RENAME));
        final var move = Buttons.create(Buttons.Kind.SECONDARY, "Move", "move", () -> onAction.accept(EntryAction.MOVE));
        final var delete = Buttons.iconOnly(Buttons.Kind.DANGER, "trash", "Delete", () -> onAction.accept(EntryAction.DELETE));
        for (final var b : List.of(rename, move, delete)) {
            b.setEnabled(!readOnly);
        }
        rename.addClassName("fb-grow");
        move.addClassName("fb-grow");
        final var primaryRow = Ui.div("fb-details-actions-row", download, star);
        final var secondaryRow = Ui.div("fb-details-actions-row", rename, move, delete);
        final var actions = Ui.div("fb-details-actions", primaryRow, secondaryRow);
        aside.add(actions);
    }

    private static Component metaRow(String label, Component value, String valueClass) {
        final var key = Ui.span("fb-details-meta-key", label);
        final var valueWrapper = Ui.span("fb-details-meta-value " + valueClass, value);
        return Ui.div("fb-details-meta-row", key, valueWrapper);
    }
}
