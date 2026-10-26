package org.vaadin.filebrowser.views;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.vaadin.filebrowser.config.FileBrowserProperties;
import org.vaadin.filebrowser.config.SecurityConfig;
import org.vaadin.filebrowser.ui.Brand;
import org.vaadin.filebrowser.ui.Icon;
import org.vaadin.filebrowser.ui.Ui;

import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

/** Provider chooser: one "Continue with …" button per configured OIDC provider. */
@Route("sign-in")
@PageTitle("Sign in · FileBrowser")
@AnonymousAllowed
public class SignInView extends Div implements BeforeEnterObserver {

    private static final String ERROR_PARAM = "error";

    private final Div error = Ui.div("fb-signin-error");

    public SignInView(ObjectProvider<ClientRegistrationRepository> repository, FileBrowserProperties properties) {
        setClassName("fb-login");

        final var title = new H1("Log in to FileBrowser");
        final var subtitle = new Paragraph("Sign in to access your file systems.");
        final var brand = new Brand(Brand.Size.XL);
        final var titles = Ui.div("", title, subtitle);
        final var heading = Ui.div("fb-login-heading", brand, titles);

        final var warnIcon = new Icon("warn");
        final var errorText = Ui.span("", "Sign-in failed. Try again, or contact your administrator.");
        error.add(warnIcon, errorText);
        error.setVisible(false);

        final var buttons = Ui.div("fb-signin-providers");
        for (final var registration : SecurityConfig.registrations(repository)) {
            final var id = registration.getRegistrationId();
            final var provider = properties.provider(id);
            final var iconName = provider.icon().name().toLowerCase().replace('_', '-');
            final var logo = new Image("oidc/" + iconName + ".svg", "");
            logo.setClassName("fb-provider-logo");
            final var label = Ui.span("", "Continue with " + provider.label());
            final var button = new Anchor(SecurityConfig.AUTHORIZATION_PATH.substring(1) + id, logo, label);
            button.setRouterIgnore(true);
            button.setClassName("fb-provider-btn");
            buttons.add(button);
        }

        final var card = Ui.div("fb-login-card", heading, error, buttons);
        final var userIcon = new Icon("user");
        final var noteText = Ui.span("", "Accounts are provisioned by your administrator.");
        final var note = Ui.div("fb-login-note", userIcon, noteText);
        final var brandRow = Brand.withName(Brand.Size.S);
        final var brandCorner = Ui.div("fb-login-brand", brandRow);
        final var column = Ui.div("fb-login-column", card, note);
        add(brandCorner, column);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        final var failed = event.getLocation().getQueryParameters().getParameters().containsKey(ERROR_PARAM);
        error.setVisible(failed);
    }
}
