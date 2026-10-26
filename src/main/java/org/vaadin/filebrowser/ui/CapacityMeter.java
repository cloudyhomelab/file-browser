package org.vaadin.filebrowser.ui;

import org.vaadin.filebrowser.data.Formats;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.RootType;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;

/** Usage line and bar. Metered storage (no total) gets a striped bar; NFS shows no usage at all. */
public class CapacityMeter extends Composite<Div> {

    public enum Style { CARD, SIDEBAR }

    public CapacityMeter(FsRoot root, Style style) {
        final var div = getContent();
        div.addClassNames("fb-capacity", "fb-capacity--" + style.name().toLowerCase(), "fb-root-" + root.type().key());
        final var cap = root.capacity();
        final var nfs = root.type() == RootType.NFS;

        if (style == Style.CARD && (nfs || cap == null)) {
            div.addClassName("fb-capacity--none");
            final var linkIcon = new Icon("link");
            final var text = new Span(nfs ? "Network mount · live" : "Object storage · usage metered");
            div.add(linkIcon, text);
            return;
        }

        final var pct = cap == null ? null : cap.percentUsed();
        final var line = Ui.div("fb-capacity-line");
        if (style == Style.CARD) {
            final var usedText = Formats.gigabytes(cap.used());
            final var used = Ui.span("fb-capacity-used", usedText);
            final var ofTotalText = cap.total() != null ? " of " + Formats.gigabytes(cap.total()) : " stored";
            final var ofTotal = new Span(ofTotalText);
            final var usage = Ui.span("", used, ofTotal);
            line.add(usage);
        } else if (nfs) {
            final var mount = new Span("Network mount");
            line.add(mount);
        } else {
            final var total = cap != null && cap.total() != null ? " / " + Formats.gigabytes(cap.total()) : "";
            final var usedText = Formats.gigabytes(cap == null ? null : cap.used());
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
