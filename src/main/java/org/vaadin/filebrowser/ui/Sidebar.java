package org.vaadin.filebrowser.ui;

import java.util.List;

import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.Located;
import org.vaadin.filebrowser.service.CurrentUser;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.html.Aside;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Nav;
import com.vaadin.flow.function.SerializableBiConsumer;
import com.vaadin.flow.function.SerializableConsumer;
import com.vaadin.flow.function.SerializableRunnable;

public class Sidebar extends Composite<Aside> {

    public record Callbacks(SerializableConsumer<List<String>> onNavigate, SerializableConsumer<FsRoot> onPickRoot,
                            SerializableRunnable onAllRoots, SerializableRunnable onStarred,
                            SerializableRunnable onLogout, SerializableBiConsumer<Located, List<String>> onDropInto) {
    }

    private final FolderTree folderTree;
    private final Div starredSlot = Ui.div("fb-sidebar-starred-slot");
    private final SerializableRunnable onStarred;

    public Sidebar(FsRoot root, List<FsRoot> browsable, SubfolderLoader folders, Catalog catalog, CurrentUser.User user,
                   Callbacks callbacks) {
        final var aside = getContent();
        aside.setClassName("fb-sidebar");
        folderTree = new FolderTree(folders, callbacks.onNavigate(), callbacks.onDropInto());
        onStarred = callbacks.onStarred();

        final var nav = new Nav(folderTree);
        nav.setClassName("fb-sidebar-nav");

        final var userName = user == null ? "" : user.name();
        final var userSub = user == null ? "" : "Signed in with " + user.providerLabel();
        final var avatar = new UserAvatar(userName, UserAvatar.Size.M);
        final var nameLabel = Ui.span("fb-sidebar-user-name", userName);
        final var subLabel = Ui.span("fb-sidebar-user-sub", userSub);
        final var userText = Ui.span("fb-sidebar-user-text", nameLabel, subLabel);
        final var logout = Ui.iconButton("logout", "Log out", callbacks.onLogout());
        final var footer = Ui.div("fb-sidebar-user", avatar, userText, logout);

        final var brandName = Brand.withName(Brand.Size.S);
        final var brand = Ui.div("fb-sidebar-brand", brandName);
        final var switcher = new RootSwitcher(root, browsable, catalog, callbacks.onPickRoot(), callbacks.onAllRoots());
        final var capacity = new CapacityMeter(root, catalog.kind(root), CapacityMeter.Style.SIDEBAR);
        final var separator = Ui.divider("fb-sidebar-sep");
        final var foldersText = Ui.span("", "Folders");
        final var foldersLabel = Ui.div("fb-sidebar-label", foldersText);
        aside.add(brand, switcher, capacity, separator, starredSlot, foldersLabel, nav, footer);
    }

    /** @param path the browsed folder, or null while the starred view is shown */
    public void update(List<String> path, boolean starredView, int starredCount) {
        folderTree.show(starredView ? null : path);
        starredSlot.removeAll();
        final var lit = starredView || starredCount > 0;
        final var starIcon = new Icon(lit ? "star" : "star-outline");
        final var label = Ui.span("fb-grow", "Starred");
        final var item = Ui.div("fb-tree-row fb-starred-nav" + (starredView ? " fb-tree-row--active" : "")
                + (lit ? " fb-starred-nav--lit" : ""), starIcon, label);
        if (starredCount > 0) {
            final var countText = String.valueOf(starredCount);
            final var count = Ui.span("fb-count" + (starredView ? " fb-count--active" : ""), countText);
            item.add(count);
        }
        item.addClickListener(e -> onStarred.run());
        starredSlot.add(item);
    }

    public void setDropActive(boolean on) {
        folderTree.setDropActive(on);
    }

    /** Re-reads folders after something was created, renamed, moved or deleted. */
    public void reloadFolders() {
        folderTree.reload();
    }
}
