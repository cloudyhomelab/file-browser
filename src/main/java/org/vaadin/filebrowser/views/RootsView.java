package org.vaadin.filebrowser.views;

import java.util.List;

import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.service.CurrentUser;
import org.vaadin.filebrowser.service.Workspace;
import org.vaadin.filebrowser.storage.StorageException;
import org.vaadin.filebrowser.ui.AppHeader;
import org.vaadin.filebrowser.ui.Buttons;
import org.vaadin.filebrowser.ui.DriveCard;
import org.vaadin.filebrowser.ui.Icon;
import org.vaadin.filebrowser.ui.MenuPosition;
import org.vaadin.filebrowser.ui.RootMenu;
import org.vaadin.filebrowser.ui.StarredBanner;
import org.vaadin.filebrowser.ui.SubfolderLoader;
import org.vaadin.filebrowser.ui.Toasts;
import org.vaadin.filebrowser.ui.Ui;
import org.vaadin.filebrowser.ui.dialog.AddRootDialog;
import org.vaadin.filebrowser.ui.dialog.RemoveRootDialog;

import jakarta.annotation.security.PermitAll;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Main;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.function.SerializableConsumer;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

/** "Your file systems" landing page. */
@PermitAll
@Route("")
@PageTitle("FileBrowser")
public class RootsView extends Div {

    private final Workspace workspace;
    private final CurrentUser currentUser;
    private final Main main = new Main();
    private final RootMenu rootMenu = new RootMenu();

    public RootsView(Workspace workspace, CurrentUser currentUser) {
        this.workspace = workspace;
        this.currentUser = currentUser;
        setClassName("fb-page");
        main.setClassName("fb-page-main");
        currentUser.get().ifPresent(user -> {
            final var header = new AppHeader(user, this::logout);
            add(header);
        });
        add(main, rootMenu);
        render();
    }

    private void render() {
        main.removeAll();
        final var roots = workspace.roots();
        final var accessible = roots.stream().filter(r -> !r.locked()).toList();
        final var restricted = roots.stream().filter(FsRoot::locked).toList();

        final var admin = currentUser.isAdmin();
        final var title = new H1("Your file systems");
        final var spacer = Ui.spacer();
        final var titlebar = Ui.div("fb-page-titlebar", title, spacer);
        if (admin) {
            final var add = Buttons.create(Buttons.Kind.PRIMARY, "Add file system", "plus", this::openAddDialog);
            titlebar.add(add);
        }
        main.add(titlebar);
        final var emptyText = admin
                ? "No file systems are connected yet. Add a folder on the server — a local disk or a mounted share — to get started."
                : "No file systems are connected yet. Ask an administrator to add one.";
        final var introText = roots.isEmpty()
                ? emptyText
                : "Pick a root to browse. You have access to " + accessible.size() + " of " + roots.size()
                        + " connected systems.";
        final var intro = new Paragraph(introText);
        intro.setClassName("fb-page-intro");
        main.add(intro);

        if (!roots.isEmpty()) {
            final var starredCount = workspace.browsableRoots().stream().mapToInt(r -> workspace.starredIn(r).size()).sum();
            final var banner = new StarredBanner(starredCount, () -> UI.getCurrent().navigate(StarredView.class));
            main.add(banner);
        }

        final var grid = Ui.div("fb-drive-grid");
        accessible.forEach(r -> grid.add(card(r)));
        if (admin) {
            final var plusIcon = new Icon("plus");
            final var addCardIcon = Ui.span("fb-addroot-card-icon", plusIcon);
            final var addCardTitle = Ui.span("fb-addroot-card-title", "Add file system");
            final var addCardSub = Ui.span("fb-addroot-card-sub", "Connect a folder on the server: a local disk or a mounted share");
            final var addCard = Ui.button("fb-addroot-card", this::openAddDialog, addCardIcon, addCardTitle, addCardSub);
            grid.add(addCard);
        }
        if (grid.getComponentCount() > 0) {
            main.add(grid);
        }

        if (!restricted.isEmpty()) {
            final var ruleLabel = Ui.span("fb-section-rule-label", "Restricted");
            final var ruleLine = Ui.divider("fb-section-rule-line");
            final var rule = Ui.div("fb-section-rule", ruleLabel, ruleLine);
            main.add(rule);
            final var lockedGrid = Ui.div("fb-drive-grid");
            restricted.forEach(r -> lockedGrid.add(card(r)));
            main.add(lockedGrid);
        }
    }

    private DriveCard card(FsRoot root) {
        final var kind = workspace.catalog().kind(root);
        final var rootParams = BrowserView.params(root.id(), List.of());
        final SerializableConsumer<MenuPosition> onMenu = currentUser.isAdmin()
                ? pos -> rootMenu.open(root, pos, this::openEditDialog, this::openRemoveDialog)
                : null;
        return new DriveCard(root, kind,
                () -> UI.getCurrent().navigate(BrowserView.class, rootParams),
                () -> Toasts.show("Access requested for “" + root.name() + "”", "mail"),
                onMenu);
    }

    private void openEditDialog(FsRoot root) {
        final var dialog = AddRootDialog.edit(workspace.catalog(), workspace::probe, root, edited -> {
            workspace.updateRoot(root, edited.name(), edited.access());
            Toasts.show("Saved “" + edited.name() + "”", "check");
            render();
        });
        dialog.open();
    }

    private void openRemoveDialog(FsRoot root) {
        final var dialog = new RemoveRootDialog(root, () -> {
            workspace.removeRoot(root);
            Toasts.show("Removed “" + root.name() + "”", "trash");
            render();
        });
        dialog.open();
    }

    private void openAddDialog() {
        final var newId = workspace.newRootId();
        final SubfolderLoader serverFolders = folder -> workspace.serverFolders("/" + String.join("/", folder));
        final var dialog = new AddRootDialog(workspace.catalog(), workspace::endpointTaken, workspace::probe,
                serverFolders, workspace::checkS3, newId, (root, s3) -> {
            try {
                workspace.addRoot(root, s3);
            } catch (StorageException e) {
                Toasts.error(e.getMessage());
                return;
            }
            Toasts.show("Connected “" + root.name() + "”", "check");
            render();
        });
        dialog.open();
    }

    private void logout() {
        currentUser.signOut();
    }
}
