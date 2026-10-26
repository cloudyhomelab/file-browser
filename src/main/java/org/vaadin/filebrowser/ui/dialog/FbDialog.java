package org.vaadin.filebrowser.ui.dialog;

import org.vaadin.filebrowser.ui.Buttons;
import org.vaadin.filebrowser.ui.Icon;
import org.vaadin.filebrowser.ui.Ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.H2;

/** The design's modal: tinted icon + title header, scrolling body, tinted footer with right-aligned actions. */
public abstract class FbDialog extends Dialog {

    public enum Tone { ACCENT, DANGER }

    public enum Width { NARROW, MEDIUM, WIDE }

    private final H2 title = new H2();

    protected FbDialog(String titleText, String icon, Tone tone, Width width) {
        addClassNames("fb-dialog", "fb-dialog--" + width.name().toLowerCase());
        title.setText(titleText);
        final var chipIcon = new Icon(icon);
        final var chip = Ui.span("fb-dialog-icon fb-dialog-icon--" + tone.name().toLowerCase(), chipIcon);
        final var head = Ui.div("fb-dialog-head", chip, title);
        getHeader().add(head);
    }

    protected void setTitleText(String text) {
        title.setText(text);
    }

    protected Button cancelButton() {
        return Buttons.create(Buttons.Kind.TERTIARY, "Cancel", null, this::close);
    }

    protected void footer(Component... components) {
        getFooter().add(components);
    }
}
