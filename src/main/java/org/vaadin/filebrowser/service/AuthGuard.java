package org.vaadin.filebrowser.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.vaadin.filebrowser.views.LoginView;

import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;

/** Every view except the login screen requires a signed-in user. */
@Component
public class AuthGuard implements VaadinServiceInitListener {

    private final ObjectProvider<CurrentUser> currentUser;

    public AuthGuard(ObjectProvider<CurrentUser> currentUser) {
        this.currentUser = currentUser;
    }

    @Override
    public void serviceInit(ServiceInitEvent event) {
        event.getSource().addUIInitListener(ui -> ui.getUI().addBeforeEnterListener(this::check));
    }

    private void check(BeforeEnterEvent event) {
        final var toLogin = LoginView.class.equals(event.getNavigationTarget());
        final var signedIn = currentUser.getObject().signedIn();
        if (!signedIn && !toLogin) {
            event.forwardTo(LoginView.class);
        } else if (signedIn && toLogin) {
            event.forwardTo("");
        }
    }
}
