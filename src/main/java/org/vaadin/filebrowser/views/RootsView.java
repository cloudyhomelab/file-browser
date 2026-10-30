package org.vaadin.filebrowser.views;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
import org.vaadin.filebrowser.ui.Switch;
import org.vaadin.filebrowser.ui.Notices;
import org.vaadin.filebrowser.ui.Ui;
import org.vaadin.filebrowser.ui.dialog.AddRootDialog;
import org.vaadin.filebrowser.ui.dialog.RemoveRootDialog;
import org.vaadin.filebrowser.ui.dialog.RootSettingsDialog;

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
        final var roots = workspace.roots().stream()
                .sorted(Comparator.comparing(FsRoot::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        final var accessible = roots.stream().filter(r -> !r.locked()).toList();
        final var restricted = roots.stream().filter(FsRoot::locked).toList();
        final var admin = currentUser.isAdmin();
        main.add(titlebar(roots, admin), intro(roots, accessible, admin));
        if (!roots.isEmpty()) {
            final var starredCount = workspace.browsableRoots().stream().mapToInt(r -> workspace.starredIn(r).size()).sum();
            final var banner = new StarredBanner(starredCount, () -> UI.getCurrent().navigate(StarredView.class));
            main.add(banner);
        }
        addCards(accessible, admin);
        if (!restricted.isEmpty()) {
            main.add(rule("Restricted"));
            final var lockedGrid = Ui.div("fb-drive-grid");
            restricted.forEach(r -> lockedGrid.add(card(r)));
            main.add(lockedGrid);
        }
    }

    private Div titlebar(List<FsRoot> roots, boolean admin) {
        final var title = new H1("Your file systems");
        final var spacer = Ui.spacer();
        final var titlebar = Ui.div("fb-page-titlebar", title, spacer);
        if (!roots.isEmpty()) {
            final var groupSwitch = new Switch("Group by type", workspace.isGroupByType(), on -> {
                workspace.setGroupByType(on);
                render();
            });
            titlebar.add(groupSwitch);
        }
        if (admin) {
            final var add = Buttons.create(Buttons.Kind.PRIMARY, "Add file system", "plus", this::openAddDialog);
            titlebar.add(add);
        }
        return titlebar;
    }

    private static Paragraph intro(List<FsRoot> roots, List<FsRoot> accessible, boolean admin) {
        final var emptyText = admin
                ? "No file systems are connected yet. Add a folder on the server — a local disk or a mounted share — to get started."
                : "No file systems are connected yet. Ask an administrator to add one.";
        final var introText = roots.isEmpty()
                ? emptyText
                : "Pick a root to browse. You have access to " + accessible.size() + " of " + roots.size()
                        + " connected systems.";
        final var intro = new Paragraph(introText);
        intro.setClassName("fb-page-intro");
        return intro;
    }

    /** The cards of the file systems the user may open, grouped by type or in one grid with the add card. */
    private void addCards(List<FsRoot> accessible, boolean admin) {
        if (workspace.isGroupByType()) {
            byType(accessible).forEach((type, group) -> {
                final var info = workspace.getCatalog().rootType(type);
                main.add(rule(info == null ? type : info.displayName()));
                final var typeGrid = Ui.div("fb-drive-grid");
                group.forEach(r -> typeGrid.add(card(r)));
                main.add(typeGrid);
            });
            return;
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
    }

    /** In the order the types are offered when adding a file system; types it no longer offers last. */
    private Map<String, List<FsRoot>> byType(List<FsRoot> roots) {
        final var order = workspace.getCatalog().rootTypeKeys();
        final var groups = new TreeMap<String, List<FsRoot>>(Comparator
                .comparingInt((String type) -> order.contains(type) ? order.indexOf(type) : order.size())
                .thenComparing(Comparator.naturalOrder()));
        roots.forEach(r -> groups.computeIfAbsent(r.type(), t -> new ArrayList<>()).add(r));
        return groups;
    }

    private static Div rule(String label) {
        final var ruleLabel = Ui.span("fb-section-rule-label", label);
        final var ruleLine = Ui.divider("fb-section-rule-line");
        return Ui.div("fb-section-rule", ruleLabel, ruleLine);
    }

    private DriveCard card(FsRoot root) {
        final var kind = workspace.getCatalog().kind(root);
        final var rootParams = BrowserView.params(root.id(), List.of());
        // a file system of a type the app no longer has a form for can only be removed
        final SerializableConsumer<FsRoot> onEdit = AddRootDialog.canEdit(workspace, root.type())
                ? this::openEditDialog : null;
        final SerializableConsumer<MenuPosition> onMenu = currentUser.isAdmin()
                ? pos -> rootMenu.open(root, pos, onEdit, this::openSettingsDialog, this::openRemoveDialog)
                : null;
        return new DriveCard(root, kind,
                () -> UI.getCurrent().navigate(BrowserView.class, rootParams),
                () -> Notices.show("Access requested for “" + root.name() + "”", "mail"),
                onMenu);
    }

    private void openEditDialog(FsRoot root) {
        final var dialog = AddRootDialog.edit(workspace, root, edited -> {
            workspace.updateRoot(root, edited.name(), edited.access());
            Notices.show("Saved “" + edited.name() + "”", "check");
            render();
        });
        dialog.open();
    }

    private void openSettingsDialog(FsRoot root) {
        final var limits = workspace.getCatalog().getRootOptionLimits();
        final var dialog = new RootSettingsDialog(root, limits, options -> {
            workspace.updateOptions(root, options);
            Notices.show("Saved settings for “" + root.name() + "”", "check");
            render();
        });
        dialog.open();
    }

    private void openRemoveDialog(FsRoot root) {
        final var dialog = new RemoveRootDialog(root, () -> {
            workspace.removeRoot(root);
            Notices.show("Removed “" + root.name() + "”", "trash");
            render();
        });
        dialog.open();
    }

    private void openAddDialog() {
        final var newId = workspace.newRootId();
        final var dialog = new AddRootDialog(workspace, newId, (root, secrets) -> {
            try {
                workspace.addRoot(root, secrets);
            } catch (StorageException e) {
                Notices.error(e.getMessage());
                return;
            }
            Notices.show("Connected “" + root.name() + "”", "check");
            render();
        });
        dialog.open();
    }

    private void logout() {
        currentUser.signOut();
    }
}
