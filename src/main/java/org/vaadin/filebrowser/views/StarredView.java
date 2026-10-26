package org.vaadin.filebrowser.views;

import java.util.List;
import java.util.stream.Stream;

import org.vaadin.filebrowser.data.Formats;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.Located;
import org.vaadin.filebrowser.data.Sort;
import org.vaadin.filebrowser.data.SortKey;
import org.vaadin.filebrowser.service.CurrentUser;
import org.vaadin.filebrowser.service.Workspace;
import org.vaadin.filebrowser.service.Workspace.Layout;
import org.vaadin.filebrowser.ui.AppHeader;
import org.vaadin.filebrowser.ui.EmptyState;
import org.vaadin.filebrowser.ui.EntryAction;
import org.vaadin.filebrowser.ui.EntryHandler;
import org.vaadin.filebrowser.ui.EntryMenu;
import org.vaadin.filebrowser.ui.FileGridView;
import org.vaadin.filebrowser.ui.FileListView;
import org.vaadin.filebrowser.ui.Icon;
import org.vaadin.filebrowser.ui.MenuPosition;
import org.vaadin.filebrowser.ui.RootChip;
import org.vaadin.filebrowser.ui.Segmented;
import org.vaadin.filebrowser.ui.Toasts;
import org.vaadin.filebrowser.ui.Ui;

import jakarta.annotation.security.PermitAll;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Main;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Section;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;

/** Starred items from every accessible file system, grouped by root. */
@PermitAll
@Route("starred")
@PageTitle("Starred · FileBrowser")
public class StarredView extends Div implements EntryHandler {

    private record Group(FsRoot root, List<Located> entries) {
    }

    private final Workspace workspace;
    private final CurrentUser currentUser;
    private final Main main = new Main();
    private final EntryMenu entryMenu = new EntryMenu();

    public StarredView(Workspace workspace, CurrentUser currentUser) {
        this.workspace = workspace;
        this.currentUser = currentUser;
        setClassName("fb-page");
        main.setClassName("fb-page-main fb-page-main--starred");
        currentUser.get().ifPresent(user -> {
            final var header = new AppHeader(user, this::logout);
            add(header);
        });
        add(main, entryMenu);
        render();
    }

    private void render() {
        main.removeAll();
        final var groups = workspace.browsableRoots().stream()
                .map(r -> new Group(r, workspace.starredIn(r)))
                .filter(g -> !g.entries().isEmpty())
                .toList();
        final var total = groups.stream().mapToInt(g -> g.entries().size()).sum();

        final var backIcon = new Icon("angle-left");
        final var backLabel = Ui.span("", "All file systems");
        final var back = Ui.button("fb-back-link", () -> UI.getCurrent().navigate(RootsView.class), backIcon, backLabel);
        final var starIcon = new Icon("star");
        final var titleText = Ui.span("", "Starred");
        final var title = new H1();
        title.add(starIcon, titleText);
        final var spacer = Ui.spacer();
        final var titlebar = Ui.div("fb-page-titlebar", title, spacer);
        if (total > 0) {
            final var listOption = Segmented.Option.icon(Layout.LIST, "list", "List");
            final var gridOption = Segmented.Option.icon(Layout.GRID, "grid", "Grid");
            final var layoutToggle = new Segmented<>("fb-seg--view", List.of(listOption, gridOption), workspace.layout(), l -> {
                workspace.setLayout(l);
                render();
            });
            titlebar.add(layoutToggle);
        }
        final var itemCount = Formats.plural(total, "item");
        final var rootCount = Formats.plural(groups.size(), "file system");
        final var intro = new Paragraph(total > 0
                ? itemCount + " pinned across " + rootCount + "."
                : "Nothing starred yet.");
        intro.setClassName("fb-page-intro");
        main.add(back, titlebar, intro);

        if (total == 0) {
            final var empty = new EmptyState(EmptyState.Kind.STARRED_ALL, null);
            empty.addClassName("fb-empty--card");
            main.add(empty);
            return;
        }

        final var sections = Ui.div("fb-starred-groups");
        for (final var g : groups) {
            final var chip = new RootChip(g.root().type(), RootChip.Size.S);
            final var name = Ui.span("fb-group-name", g.root().name());
            final var groupCount = Formats.plural(g.entries().size(), "item");
            final var count = Ui.span("fb-group-count", groupCount);
            final var line = Ui.divider("fb-group-line");
            final var head = Ui.div("fb-group-head", chip, name, count, line);
            final var section = new Section(head);
            final var entries = workspace.layout() == Layout.GRID
                    ? new FileGridView(g.entries(), this, workspace.catalog())
                    : new FileListView(g.entries(), this, workspace.density(), workspace.catalog());
            section.add(entries);
            sections.add(section);
        }
        main.add(sections);
    }

    private void logout() {
        currentUser.signOut();
    }

    @Override
    public boolean selectable() {
        return false;
    }

    @Override
    public boolean selected(Located entry) {
        return false;
    }

    @Override
    public boolean focused(Located entry) {
        return false;
    }

    @Override
    public boolean starred(Located entry) {
        return workspace.isStarred(entry.rootId(), entry.entry().path());
    }

    @Override
    public void toggleSelected(Located entry) {
    }

    @Override
    public void toggleAll(boolean on) {
    }

    @Override
    public void open(Located entry) {
        final var target = entry.entry().folder()
                ? Stream.concat(entry.location().stream(), Stream.of(entry.entry().name())).toList()
                : entry.location();
        final var query = entry.entry().folder() ? QueryParameters.empty() : QueryParameters.of("focus", entry.entry().path());
        final var params = BrowserView.params(entry.rootId(), target);
        UI.getCurrent().navigate(BrowserView.class, params, query);
    }

    @Override
    public void selectRow(Located entry) {
        open(entry);
    }

    @Override
    public void contextMenu(Located entry, MenuPosition position) {
        entryMenu.open(entry, position, true, starred(entry), false, (action, located) -> {
            if (action == EntryAction.OPEN) {
                open(located);
            } else if (action == EntryAction.DOWNLOAD) {
                Toasts.show("Downloading “" + located.entry().name() + "”…", "download");
            } else if (action == EntryAction.STAR) {
                toggleStar(located);
            }
        });
    }

    @Override
    public void toggleStar(Located entry) {
        final var on = starred(entry);
        workspace.setStarred(entry.rootId(), entry.entry().path(), !on);
        Toasts.show(on ? "Removed “" + entry.entry().name() + "” from starred" : "Added “" + entry.entry().name() + "” to starred", "star");
        render();
    }

    @Override
    public Sort sort() {
        return workspace.sort();
    }

    @Override
    public void sortBy(SortKey key) {
        workspace.setSort(workspace.sort().toggle(key));
        render();
    }
}
