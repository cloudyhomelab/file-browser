package org.vaadin.filebrowser.ui;

import java.util.Locale;

import com.vaadin.flow.component.html.Span;

/** The black folder mark; size comes from the modifier class. */
public class Brand extends Span {

    public enum Size { S, M, XL }

    public Brand(Size size) {
        addClassNames("fb-brand", "fb-brand--" + size.name().toLowerCase(Locale.ROOT));
        final var mark = new Icon("brand");
        add(mark);
    }

    public static Span withName(Size size) {
        final var brand = new Brand(size);
        final var name = Ui.span("fb-brand-name", "FileBrowser");
        return Ui.span("fb-brand-row fb-brand-row--" + size.name().toLowerCase(Locale.ROOT), brand, name);
    }
}
