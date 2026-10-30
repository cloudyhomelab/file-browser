package org.vaadin.filebrowser.ui;

import org.vaadin.filebrowser.data.Formats;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.function.SerializableRunnable;

public class StarredBanner extends Composite<Card> {

    public StarredBanner(int count, SerializableRunnable onOpen) {
        final var card = getContent();
        card.setClassName("fb-starred-banner");
        final var enabled = count > 0;
        final var text = enabled
                ? Formats.plural(count, "item") + " pinned across your file systems"
                : "Star a file or folder and it shows up here for quick access";
        final var starIcon = new Icon(enabled ? "star" : "star-outline");
        final var icon = Ui.span("fb-starred-banner-icon", starIcon);
        card.setHeaderPrefix(icon);
        // a component title, unlike the String one, is not announced as a heading
        final var title = Ui.span("", "Starred");
        card.setTitle(title);
        final var sub = Ui.span("", text);
        card.setSubtitle(sub);
        if (enabled) {
            card.addClassName("fb-starred-banner--enabled");
            card.setAriaRole("button");
            card.getElement().setAttribute("tabindex", "0");
            card.getElement().addEventListener("click", e -> onOpen.run());
            final var linkText = Ui.span("", "View all");
            final var arrow = new Icon("arrow-right");
            final var link = Ui.span("fb-starred-banner-link", linkText, arrow);
            card.setHeaderSuffix(link);
        }
    }
}
