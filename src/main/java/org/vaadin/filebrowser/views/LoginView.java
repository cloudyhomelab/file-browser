package org.vaadin.filebrowser.views;

import java.util.regex.Pattern;

import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.service.CurrentUser;
import org.vaadin.filebrowser.ui.Brand;
import org.vaadin.filebrowser.ui.Buttons;
import org.vaadin.filebrowser.ui.Icon;
import org.vaadin.filebrowser.ui.Ui;

import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.NativeLabel;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.textfield.Autocomplete;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.textfield.TextFieldBase;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

@Route("login")
@PageTitle("Log in · FileBrowser")
public class LoginView extends Div {

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final CurrentUser currentUser;
    private final TextField email = new TextField();
    private final PasswordField password = new PasswordField();

    public LoginView(CurrentUser currentUser, Catalog catalog) {
        this.currentUser = currentUser;
        setClassName("fb-login");

        final var google = Buttons.create(Buttons.Kind.SECONDARY, "Continue with Google", "google", () -> {
            final var account = catalog.googleAccount();
            final var googleUser = new CurrentUser.User(account.email(), account.name(), true);
            finish(googleUser);
        });
        google.addClassNames("fb-btn--block", "fb-login-google");

        final var orLineBefore = Ui.divider("fb-login-or-line");
        final var orText = Ui.span("fb-login-or-text", "or");
        final var orLineAfter = Ui.divider("fb-login-or-line");
        final var or = Ui.div("fb-login-or", orLineBefore, orText, orLineAfter);

        email.setPlaceholder("you@company.com");
        email.setAutocomplete(Autocomplete.USERNAME);
        email.setId("fb-login-email");
        email.addValueChangeListener(e -> email.setInvalid(false));
        password.setPlaceholder("Enter your password");
        password.setAutocomplete(Autocomplete.CURRENT_PASSWORD);
        password.setId("fb-login-password");
        password.addValueChangeListener(e -> password.setInvalid(false));
        for (final TextFieldBase<?, String> field : new TextFieldBase[]{email, password}) {
            field.addClassNames("fb-field", "fb-field--l");
            field.setWidthFull();
            field.addKeyPressListener(Key.ENTER, e -> submit());
        }

        final var forgot = new Anchor("#", "Forgot password?");
        forgot.setClassName("fb-login-forgot");
        forgot.getElement().addEventListener("click", e -> { }).preventDefault();

        final var logIn = Buttons.create(Buttons.Kind.PRIMARY, "Log in", null, this::submit);
        logIn.addClassNames("fb-btn--block", "fb-login-submit");

        final var emailLabel = labelRow("Email", email.getId().orElseThrow(), null);
        final var emailField = Ui.div("fb-login-field", emailLabel, email);
        final var passwordLabel = labelRow("Password", password.getId().orElseThrow(), forgot);
        final var passwordField = Ui.div("fb-login-field", passwordLabel, password);
        final var form = Ui.div("fb-login-form", emailField, passwordField, logIn);

        final var title = new H1("Log in to FileBrowser");
        final var subtitle = new Paragraph("Sign in to access your file systems.");
        final var titles = Ui.div("", title, subtitle);
        final var brandMark = new Brand(Brand.Size.XL);
        final var heading = Ui.div("fb-login-heading", brandMark, titles);

        final var card = Ui.div("fb-login-card", heading, google, or, form);
        final var noteIcon = new Icon("user");
        final var noteText = Ui.span("", "Accounts are provisioned by your administrator.");
        final var note = Ui.div("fb-login-note", noteIcon, noteText);

        final var brandName = Brand.withName(Brand.Size.S);
        final var brandCorner = Ui.div("fb-login-brand", brandName);
        final var column = Ui.div("fb-login-column", card, note);
        add(brandCorner, column);
    }

    private static Div labelRow(String text, String forId, Anchor action) {
        final var label = new NativeLabel(text);
        label.setFor(forId);
        final var row = Ui.div("fb-label-row", label);
        if (action != null) {
            row.add(action);
        }
        return row;
    }

    private void submit() {
        final var address = email.getValue().trim();
        final var emailError = address.isEmpty() ? "Enter your email address."
                : EMAIL.matcher(address).matches() ? null : "Enter a valid email address.";
        final var passwordError = password.getValue().isEmpty() ? "Enter your password." : null;
        email.setErrorMessage(emailError);
        email.setInvalid(emailError != null);
        password.setErrorMessage(passwordError);
        password.setInvalid(passwordError != null);
        if (emailError == null && passwordError == null) {
            final var name = CurrentUser.nameFromEmail(address);
            final var passwordUser = new CurrentUser.User(address, name, false);
            finish(passwordUser);
        }
    }

    private void finish(CurrentUser.User user) {
        currentUser.signIn(user);
        UI.getCurrent().navigate(RootsView.class);
    }
}
