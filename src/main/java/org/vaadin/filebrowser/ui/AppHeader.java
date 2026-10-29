package org.vaadin.filebrowser.ui;

import org.vaadin.filebrowser.service.CurrentUser;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.html.Header;
import com.vaadin.flow.component.popover.PopoverPosition;
import com.vaadin.flow.function.SerializableRunnable;

/** Top bar of the full-width pages (file-system picker, starred). */
public class AppHeader extends Composite<Header> {

    public AppHeader(CurrentUser.User user, SerializableRunnable onLogout) {
        getContent().setClassName("fb-topbar");
        final var brand = Brand.withName(Brand.Size.M);
        final var spacer = Ui.spacer();
        final var bell = new NotificationBell(PopoverPosition.BOTTOM_END);
        final var accountMenu = new AccountMenu(user, onLogout);
        getContent().add(brand, spacer, bell, accountMenu);
    }
}
