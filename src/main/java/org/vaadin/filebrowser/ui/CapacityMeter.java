package org.vaadin.filebrowser.ui;

import java.util.Locale;

import org.vaadin.filebrowser.data.Formats;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.KindInfo;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;

/** Usage line and bar. Storage without a fixed size gets a striped bar; unknown usage gets a note instead. */
public class CapacityMeter extends Composite<Div> {

    public enum Style { CARD, SIDEBAR }

    public CapacityMeter(FsRoot root, KindInfo kind, Style style) {
        final var div = getContent();
        div.addClassNames("fb-capacity", "fb-capacity--" + style.name().toLowerCase(Locale.ROOT), kind.tintClass());
        final var cap = root.capacity();

        if (cap == null) {
            div.addClassName("fb-capacity--none");
            final var reason = root.online() ? null : root.status().reason();
            final var icon = new Icon(root.online() ? "note" : "warn");
            final var note = reason != null ? reason
                    : !root.online() ? "Unavailable"
                    : kind.usageNote() != null ? kind.usageNote()
                    : "Usage unavailable";
            final var text = new Span(note);
            div.getElement().getClassList().set("fb-capacity--reason", reason != null);
            div.add(icon, text);
            return;
        }

        final var pct = cap.percentUsed();
        final var line = Ui.div("fb-capacity-line");
        final var usedText = Formats.gigabytes(cap.used());
        if (style == Style.CARD) {
            final var used = Ui.span("fb-capacity-used", usedText);
            final var ofTotalText = cap.total() != null ? " of " + Formats.gigabytes(cap.total()) : " stored";
            final var ofTotal = new Span(ofTotalText);
            final var usage = Ui.span("", used, ofTotal);
            line.add(usage);
        } else {
            final var total = cap.total() != null ? " / " + Formats.gigabytes(cap.total()) : "";
            final var usage = new Span(usedText + total);
            line.add(usage);
        }
        if (pct != null) {
            final var percent = Ui.span("fb-capacity-pct", pct + "%");
            line.add(percent);
        }

        final var bar = Ui.div("fb-capacity-bar");
        if (pct != null) {
            final var fill = Ui.div("fb-capacity-fill");
            fill.getStyle().setWidth(pct + "%");
            bar.add(fill);
        } else {
            bar.addClassName("fb-capacity-bar--striped");
        }
        div.add(line, bar);
    }
}
