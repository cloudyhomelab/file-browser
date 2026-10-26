package org.vaadin.filebrowser.ui;

import org.vaadin.filebrowser.data.Formats;
import org.vaadin.filebrowser.service.CurrentUser;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.popover.Popover;
import com.vaadin.flow.component.popover.PopoverPosition;
import com.vaadin.flow.component.popover.PopoverVariant;
import com.vaadin.flow.function.SerializableRunnable;

public class AccountMenu extends Composite<Div> {

    public AccountMenu(CurrentUser.User user, SerializableRunnable onLogout) {
        final var trigger = new NativeButton();
        trigger.setClassName("fb-account-trigger");
        final var avatar = new UserAvatar(user.name(), UserAvatar.Size.M);
        final var firstName = Formats.firstName(user.name());
        final var firstNameLabel = Ui.span("fb-account-first", firstName);
        final var caret = new Icon("angle-down");
        trigger.add(avatar, firstNameLabel, caret);
        trigger.getElement().setAttribute("aria-label", "Account");

        final var popover = new Popover();
        popover.setTarget(trigger);
        popover.setPosition(PopoverPosition.BOTTOM_END);
        popover.addThemeVariants(PopoverVariant.NO_PADDING);
        popover.addOpenedChangeListener(e -> trigger.getElement().getClassList().set("fb-open", e.isOpened()));

        final var largeAvatar = new UserAvatar(user.name(), UserAvatar.Size.L);
        final var nameText = Ui.span("", user.name());
        final var name = Ui.div("fb-account-name", nameText);
        final var emailText = Ui.span("", user.email());
        final var email = Ui.div("fb-account-email", emailText);
        final var names = Ui.div("fb-account-names", name, email);
        final var identity = Ui.div("fb-account-identity", largeAvatar, names);
        final var logoutIcon = new Icon("logout-door");
        final var logoutLabel = Ui.span("", "Log out");
        final var logout = Ui.button("fb-menu-item", () -> {
            popover.close();
            onLogout.run();
        }, logoutIcon, logoutLabel);
        final var separator = Ui.divider("fb-menu-sep");
        final var menu = Ui.div("fb-menu fb-account-menu", identity, separator, logout);
        popover.add(menu);

        getContent().setClassName("fb-account");
        getContent().add(trigger, popover);
    }
}
