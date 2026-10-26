package org.vaadin.filebrowser.views;

import java.util.List;

import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.service.CurrentUser;
import org.vaadin.filebrowser.service.Workspace;
import org.vaadin.filebrowser.ui.AppHeader;
import org.vaadin.filebrowser.ui.Buttons;
import org.vaadin.filebrowser.ui.DriveCard;
import org.vaadin.filebrowser.ui.Icon;
import org.vaadin.filebrowser.ui.StarredBanner;
import org.vaadin.filebrowser.ui.Toasts;
import org.vaadin.filebrowser.ui.Ui;
import org.vaadin.filebrowser.ui.dialog.AddRootDialog;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Main;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

/** "Your file systems" landing page. */
@Route("")
@PageTitle("FileBrowser")
public class RootsView extends Div {

    private final Workspace workspace;
    private final CurrentUser currentUser;
    private final Main main = new Main();

    public RootsView(Workspace workspace, CurrentUser currentUser) {
        this.workspace = workspace;
        this.currentUser = currentUser;
        setClassName("fb-page");
        main.setClassName("fb-page-main");
        currentUser.get().ifPresent(user -> {
            final var header = new AppHeader(user, this::logout);
            add(header);
        });
        add(main);
        render();
    }

    private void render() {
        main.removeAll();
        final var roots = workspace.roots();
        final var accessible = roots.stream().filter(r -> !r.locked()).toList();
        final var restricted = roots.stream().filter(FsRoot::locked).toList();

        final var add = Buttons.create(Buttons.Kind.PRIMARY, "Add file system", "plus", this::openAddDialog);
        final var title = new H1("Your file systems");
        final var spacer = Ui.spacer();
        final var titlebar = Ui.div("fb-page-titlebar", title, spacer, add);
        main.add(titlebar);
        final var intro = new Paragraph("Pick a root to browse. You have access to " + accessible.size() + " of "
                + roots.size() + " connected systems.");
        intro.setClassName("fb-page-intro");
        main.add(intro);

        final var starredCount = workspace.browsableRoots().stream().mapToInt(r -> workspace.starredIn(r).size()).sum();
        final var banner = new StarredBanner(starredCount, () -> UI.getCurrent().navigate(StarredView.class));
        main.add(banner);

        final var grid = Ui.div("fb-drive-grid");
        accessible.forEach(r -> grid.add(card(r)));
        final var plusIcon = new Icon("plus");
        final var addCardIcon = Ui.span("fb-addroot-card-icon", plusIcon);
        final var addCardTitle = Ui.span("fb-addroot-card-title", "Add file system");
        final var addCardSub = Ui.span("fb-addroot-card-sub", "Connect a local disk, NFS share or S3 bucket");
        final var addCard = Ui.button("fb-addroot-card", this::openAddDialog, addCardIcon, addCardTitle, addCardSub);
        grid.add(addCard);
        main.add(grid);

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
        final var typeInfo = workspace.catalog().rootType(root.type());
        final var rootParams = BrowserView.params(root.id(), List.of());
        return new DriveCard(root, typeInfo,
                () -> UI.getCurrent().navigate(BrowserView.class, rootParams),
                () -> Toasts.show("Access requested for “" + root.name() + "”", "mail"));
    }

    private void openAddDialog() {
        final var newId = workspace.newRootId();
        final var dialog = new AddRootDialog(workspace.catalog(), workspace::endpointTaken, newId, root -> {
            workspace.addRoot(root);
            Toasts.show("Connected “" + root.name() + "”", "check");
            render();
        });
        dialog.open();
    }

    private void logout() {
        currentUser.signOut();
        UI.getCurrent().navigate(LoginView.class);
    }
}
