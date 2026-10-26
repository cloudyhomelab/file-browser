package org.vaadin.filebrowser.ui;

import org.vaadin.filebrowser.data.Formats;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.function.SerializableRunnable;

public class StarredBanner extends Composite<Div> {

    public StarredBanner(int count, SerializableRunnable onOpen) {
        final var div = getContent();
        div.setClassName("fb-starred-banner");
        final var enabled = count > 0;
        final var text = enabled
                ? Formats.plural(count, "item") + " pinned across your file systems"
                : "Star a file or folder and it shows up here for quick access";
        final var starIcon = new Icon(enabled ? "star" : "star-outline");
        final var icon = Ui.span("fb-starred-banner-icon", starIcon);
        final var titleText = Ui.span("", "Starred");
        final var title = Ui.div("fb-starred-banner-title", titleText);
        final var subText = Ui.span("", text);
        final var sub = Ui.div("fb-starred-banner-sub", subText);
        final var textBlock = Ui.div("fb-starred-banner-text", title, sub);
        div.add(icon, textBlock);
        if (enabled) {
            div.addClassName("fb-starred-banner--enabled");
            div.getElement().setAttribute("role", "button");
            div.getElement().setAttribute("tabindex", "0");
            div.addClickListener(e -> onOpen.run());
            final var linkText = Ui.span("", "View all");
            final var arrow = new Icon("arrow-right");
            final var link = Ui.span("fb-starred-banner-link", linkText, arrow);
            div.add(link);
        }
    }
}
