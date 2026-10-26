package org.vaadin.filebrowser.service;

import java.io.Serializable;
import java.util.Optional;

import com.vaadin.flow.spring.annotation.SpringComponent;
import com.vaadin.flow.spring.annotation.VaadinSessionScope;

/**
 * Demo sign-in state. Any well-formed email with a non-empty password is accepted, and the Google
 * button signs in a fixed demo account; a real deployment replaces this with Spring Security.
 */
@SpringComponent
@VaadinSessionScope
public class CurrentUser implements Serializable {

    public record User(String email, String name, boolean viaGoogle) implements Serializable {
    }

    private User user;

    public Optional<User> get() {
        return Optional.ofNullable(user);
    }

    public boolean signedIn() {
        return user != null;
    }

    public void signIn(User signedIn) {
        this.user = signedIn;
    }

    public void signOut() {
        this.user = null;
    }

    public static String nameFromEmail(String email) {
        final var local = email.split("@")[0].replaceAll("[._-]+", " ").trim();
        if (local.isEmpty()) {
            return "User";
        }
        final var sb = new StringBuilder();
        for (final var part : local.split(" ")) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
