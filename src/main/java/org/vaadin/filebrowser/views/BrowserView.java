package org.vaadin.filebrowser.views;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.vaadin.filebrowser.data.FsEntry;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.IncomingFile;
import org.vaadin.filebrowser.data.Located;
import org.vaadin.filebrowser.data.Sort;
import org.vaadin.filebrowser.data.SortKey;
import org.vaadin.filebrowser.service.CurrentUser;
import org.vaadin.filebrowser.service.Workspace;
import org.vaadin.filebrowser.service.Workspace.ConflictChoice;
import org.vaadin.filebrowser.service.Workspace.Density;
import org.vaadin.filebrowser.service.Workspace.Layout;
import org.vaadin.filebrowser.service.Workspace.PlannedUpload;
import org.vaadin.filebrowser.ui.Badge;
import org.vaadin.filebrowser.ui.BulkBar;
import org.vaadin.filebrowser.ui.DetailsPanel;
import org.vaadin.filebrowser.ui.EmptyState;
import org.vaadin.filebrowser.ui.EntryAction;
import org.vaadin.filebrowser.ui.EntryHandler;
import org.vaadin.filebrowser.ui.EntryMenu;
import org.vaadin.filebrowser.ui.FileDropArea;
import org.vaadin.filebrowser.ui.FileGridView;
import org.vaadin.filebrowser.ui.FileListView;
import org.vaadin.filebrowser.ui.HeaderBulkActions;
import org.vaadin.filebrowser.ui.Icon;
import org.vaadin.filebrowser.ui.MenuPosition;
import org.vaadin.filebrowser.ui.RootChip;
import org.vaadin.filebrowser.ui.Segmented;
import org.vaadin.filebrowser.ui.Sidebar;
import org.vaadin.filebrowser.ui.Toasts;
import org.vaadin.filebrowser.ui.Toolbar;
import org.vaadin.filebrowser.ui.Toolbar.Scope;
import org.vaadin.filebrowser.ui.Ui;
import org.vaadin.filebrowser.ui.UploadDock;
import org.vaadin.filebrowser.ui.dialog.ConflictDialog;
import org.vaadin.filebrowser.ui.dialog.DeleteDialog;
import org.vaadin.filebrowser.ui.dialog.MoveDialog;
import org.vaadin.filebrowser.ui.dialog.NameDialog;
import org.vaadin.filebrowser.ui.dialog.UploadDialog;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.shared.Registration;

/** Browses one file system: sidebar tree, header with breadcrumb and toolbar, list/grid, details. */
@Route("fs/:rootId/:path*")
public class BrowserView extends Div implements BeforeEnterObserver, HasDynamicTitle, EntryHandler {

    private static final String ROOT_PARAM = "rootId";
    private static final String PATH_PARAM = "path";
    private static final String FOCUS_PARAM = "focus";
    private static final int UPLOAD_POLL_MS = 320;

    private final Workspace workspace;
    private final CurrentUser currentUser;

    private final Div sidebarSlot = Ui.div("fb-sidebar-slot");
    private final FileDropArea main;
    private final Div titleRow = Ui.div("fb-header-row");
    private final Toolbar toolbar;
    private final Div content = Ui.div("fb-content");
    private final Div detailsSlot = Ui.div("fb-details-slot");
    private final Div bulkSlot = Ui.div("fb-bulk-slot");
    private final UploadDock dock;
    private final EntryMenu entryMenu = new EntryMenu();

    private FsRoot root;
    private List<String> path = List.of();
    private Sidebar sidebar;
    private FileListView listView;
    private String query = "";
    private Scope scope = Scope.FOLDER;
    private final Set<String> selected = new HashSet<>();
    private String focused;
    private boolean starredView;
    private boolean resetting;
    private Registration pollRegistration;

    public BrowserView(Workspace workspace, CurrentUser currentUser) {
        this.workspace = workspace;
        this.currentUser = currentUser;
        setClassName("fb-browser");

        final var toolbarCallbacks = new Toolbar.Callbacks(this::onQuery, this::setScope, this::sortBy,
                this::toggleDensity, this::setLayout, this::newFolder, this::openUploadDialog);
        toolbar = new Toolbar(toolbarCallbacks);
        dock = new UploadDock(workspace.catalog(), this::clearUploads);

        main = new FileDropArea(workspace.catalog(), false);
        main.addClassName("fb-main");
        main.onFiles(this::onFilesDropped);
        final var uploadIcon = new Icon("upload");
        final var dropIcon = Ui.span("fb-drop-overlay-icon", uploadIcon);
        final var dropTitle = Ui.span("fb-drop-overlay-title", "Drop files to upload");
        final var dropSub = Ui.span("fb-drop-overlay-sub", "They'll be added to this folder");
        final var dropOverlay = Ui.div("fb-drop-overlay", dropIcon, dropTitle, dropSub);
        final var header = Ui.div("fb-header", titleRow, toolbar);
        final var body = Ui.div("fb-body", content, detailsSlot);
        main.add(header, body, dropOverlay, bulkSlot, dock);

        add(sidebarSlot, main, entryMenu);
    }

    public static RouteParameters params(String rootId, List<String> path) {
        if (path.isEmpty()) {
            return new RouteParameters(ROOT_PARAM, rootId);
        }
        final var joinedPath = String.join("/", path);
        final var values = Map.of(ROOT_PARAM, rootId, PATH_PARAM, joinedPath);
        return new RouteParameters(values);
    }

    // ---- routing --------------------------------------------------------------------------

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        final var rootId = event.getRouteParameters().get(ROOT_PARAM).orElse("");
        final var target = workspace.root(rootId).filter(FsRoot::browsable);
        if (target.isEmpty()) {
            event.forwardTo(RootsView.class);
            return;
        }
        final var newPath = event.getRouteParameters().getWildcard(PATH_PARAM);
        if (workspace.dir(target.get().id(), newPath) == null) {
            final var rootParams = params(target.get().id(), List.of());
            event.forwardTo(BrowserView.class, rootParams);
            return;
        }
        final var rootChanged = root == null || !root.id().equals(target.get().id());
        root = target.get();
        path = List.copyOf(newPath);
        if (rootChanged) {
            final var sidebarCallbacks = new Sidebar.Callbacks(this::navTo, this::openRoot, this::backToRoots,
                    this::showStarred, this::logout);
            final var browsable = workspace.browsableRoots();
            final var tree = workspace.tree(root.id());
            final var user = currentUser.get().orElse(null);
            sidebar = new Sidebar(root, browsable, tree, workspace.catalog(), user, sidebarCallbacks);
            sidebarSlot.removeAll();
            sidebarSlot.add(sidebar);
        }
        resetView();
        focused = event.getLocation().getQueryParameters().getSingleParameter(FOCUS_PARAM).orElse(null);
        refresh();
    }

    @Override
    public String getPageTitle() {
        return root == null ? "FileBrowser" : root.name() + " · FileBrowser";
    }

    private void navigate(String rootId, List<String> to, String focusId) {
        final var query = focusId == null ? QueryParameters.empty() : QueryParameters.of(FOCUS_PARAM, focusId);
        final var params = params(rootId, to);
        UI.getCurrent().navigate(BrowserView.class, params, query);
    }

    private void navTo(List<String> to) {
        navigate(root.id(), to, null);
    }

    private void openRoot(FsRoot target) {
        navigate(target.id(), List.of(), null);
    }

    private void setScope(Scope newScope) {
        scope = newScope;
        refresh();
    }

    private void toggleDensity() {
        workspace.setDensity(workspace.density() == Density.DENSE ? Density.COMFORTABLE : Density.DENSE);
        refresh();
    }

    private void setLayout(Layout layout) {
        workspace.setLayout(layout);
        refresh();
    }

    private void clearUploads() {
        workspace.clearUploads();
        refresh();
    }

    private void backToRoots() {
        UI.getCurrent().navigate(RootsView.class);
    }

    private void logout() {
        currentUser.signOut();
        UI.getCurrent().navigate(LoginView.class);
    }

    private void resetView() {
        selected.clear();
        focused = null;
        starredView = false;
        query = "";
        resetting = true;
        toolbar.clearSearch();
        resetting = false;
        entryMenu.close();
    }

    private void showStarred() {
        resetView();
        starredView = true;
        refresh();
    }

    // ---- derived state --------------------------------------------------------------------

    private boolean readOnly() {
        return root.readOnly();
    }

    private boolean treeSearch() {
        return !query.isBlank() && scope == Scope.TREE;
    }

    private List<FsEntry> here() {
        final var dir = workspace.dir(root.id(), path);
        return dir == null ? List.of() : dir;
    }

    private List<Located> displayEntries() {
        if (starredView) {
            return workspace.starredIn(root);
        }
        final var q = query.trim().toLowerCase(Locale.ROOT);
        final List<Located> filtered;
        if (treeSearch()) {
            filtered = workspace.search(root, q);
        } else {
            filtered = here().stream()
                    .filter(e -> q.isEmpty() || e.name().toLowerCase(Locale.ROOT).contains(q))
                    .map(e -> Located.here(e, root.id()))
                    .toList();
        }
        return workspace.sort().apply(filtered);
    }

    private FsEntry focusedEntry() {
        if (focused == null) {
            return null;
        }
        return here().stream().filter(e -> e.id().equals(focused)).findFirst().orElse(null);
    }

    /** The folder an entry lives in: its own location for search hits and starred items. */
    private List<String> folderOf(Located e) {
        return e.elsewhere() ? e.location() : path;
    }

    // ---- rendering ------------------------------------------------------------------------

    private void refresh() {
        final var entries = displayEntries();
        final var starredCount = workspace.starredIn(root).size();
        sidebar.update(path, starredView, starredCount);
        renderTitleRow(entries.size());
        main.getElement().getClassList().set("fb-main--starred", starredView);
        toolbar.setVisible(!starredView);
        if (!starredView) {
            final var bulk = selected.isEmpty() ? null
                    : new HeaderBulkActions(selected.size(), readOnly(), a -> handleAction(a, null), this::clearSelection);
            final var toolbarState = new Toolbar.State(workspace.sort(), workspace.density(), workspace.layout(), scope,
                    readOnly(), bulk);
            toolbar.render(toolbarState);
        }
        renderContent(entries);
        renderDetails();
        bulkSlot.removeAll();
        if (!starredView && !selected.isEmpty()) {
            final var bulkBar = new BulkBar(selected.size(), readOnly(), a -> handleAction(a, null), this::clearSelection);
            bulkSlot.add(bulkBar);
        }
        dock.show(workspace.uploads());
    }

    private void renderTitleRow(int count) {
        titleRow.removeAll();
        final var back = Ui.iconButton("angle-left", "All file systems", this::backToRoots);
        back.addClassName("fb-back-btn");
        titleRow.add(back);
        if (starredView) {
            final var starIcon = new Icon("star");
            final var starredLabel = Ui.span("", "Starred");
            final var rootLabel = Ui.span("fb-header-starred-root", "· " + root.name());
            final var starredTitle = Ui.span("fb-header-starred", starIcon, starredLabel, rootLabel);
            titleRow.add(starredTitle);
        } else {
            final var crumbs = breadcrumb();
            titleRow.add(crumbs);
            if (readOnly()) {
                final var readOnlyBadge = new Badge(Badge.Tone.WARNING, "Read only", true);
                titleRow.add(readOnlyBadge);
            }
        }
        final String noun = starredView ? "starred item" : treeSearch() ? "result" : "item";
        final var spacer = Ui.spacer();
        final var countLabel = Ui.span("fb-header-count", count + " " + noun + (count == 1 ? "" : "s"));
        titleRow.add(spacer, countLabel);
        if (starredView) {
            final var listOption = Segmented.Option.icon(Layout.LIST, "list", "List");
            final var gridOption = Segmented.Option.icon(Layout.GRID, "grid", "Grid");
            final var layoutToggle = new Segmented<>("fb-seg--view", List.of(listOption, gridOption), workspace.layout(),
                    this::setLayout);
            titleRow.add(layoutToggle);
        }
    }

    private Div breadcrumb() {
        final var crumbs = Ui.div("fb-breadcrumb");
        final var rootChip = new RootChip(root.type(), RootChip.Size.XS);
        final var rootName = Ui.span("", root.name());
        final var rootCrumb = Ui.button("fb-crumb fb-crumb--root", () -> navTo(List.of()), rootChip, rootName);
        crumbs.add(rootCrumb);
        for (int i = 0; i < path.size(); i++) {
            final var target = List.copyOf(path.subList(0, i + 1));
            final var last = i == path.size() - 1;
            final var separatorIcon = new Icon("angle-right");
            final var separator = Ui.span("fb-crumb-sep", separatorIcon);
            final var segmentName = Ui.span("", path.get(i));
            final var crumb = Ui.button("fb-crumb" + (last ? " fb-crumb--current" : ""), () -> navTo(target), segmentName);
            crumbs.add(separator, crumb);
        }
        return crumbs;
    }

    private void renderContent(List<Located> entries) {
        content.removeAll();
        listView = null;
        if (entries.isEmpty()) {
            final EmptyState.Kind kind = starredView ? EmptyState.Kind.STARRED_ROOT
                    : query.isBlank() ? EmptyState.Kind.FOLDER : EmptyState.Kind.SEARCH;
            final var canUpload = kind == EmptyState.Kind.FOLDER && !readOnly();
            final var emptyState = new EmptyState(kind, canUpload ? this::openUploadDialog : null);
            content.add(emptyState);
            return;
        }
        if (workspace.layout() == Layout.LIST) {
            listView = new FileListView(entries, this, workspace.density(), workspace.catalog(), workspace.catalog().referenceDate());
            content.add(listView);
        } else {
            final var gridView = new FileGridView(entries, this, workspace.catalog());
            content.add(gridView);
        }
    }

    private void renderDetails() {
        detailsSlot.removeAll();
        final var entry = focusedEntry();
        if (entry != null && !starredView) {
            final var located = Located.here(entry, root.id());
            final var starred = workspace.isStarred(root.id(), entry.id());
            final var details = new DetailsPanel(entry, root, path, readOnly(), starred, workspace.catalog(),
                    workspace.catalog().referenceDate(), () -> setFocused(null), a -> handleAction(a, located));
            detailsSlot.add(details);
        }
    }

    private void setFocused(String id) {
        focused = id;
        if (listView != null) {
            listView.refreshFocus();
        }
        renderDetails();
    }

    // ---- EntryHandler ---------------------------------------------------------------------

    @Override
    public boolean selectable() {
        return !starredView;
    }

    @Override
    public boolean selected(Located entry) {
        return selected.contains(entry.entry().id());
    }

    @Override
    public boolean focused(Located entry) {
        return entry.entry().id().equals(focused);
    }

    @Override
    public boolean starred(Located entry) {
        return workspace.isStarred(entry.rootId(), entry.entry().id());
    }

    @Override
    public void toggleSelected(Located entry) {
        if (!selected.remove(entry.entry().id())) {
            selected.add(entry.entry().id());
        }
        refresh();
    }

    @Override
    public void toggleAll(boolean on) {
        selected.clear();
        if (on) {
            displayEntries().forEach(e -> selected.add(e.entry().id()));
        }
        refresh();
    }

    @Override
    public void open(Located entry) {
        if (starredView) {
            openStarred(entry);
            return;
        }
        final var entryValue = entry.entry();
        if (entryValue.folder()) {
            final var folderPath = append(folderOf(entry), entryValue.name());
            navTo(folderPath);
        } else if (entry.elsewhere()) {
            navigate(root.id(), entry.location(), entryValue.id());
        } else {
            setFocused(entryValue.id());
        }
    }

    @Override
    public void selectRow(Located entry) {
        if (starredView || entry.elsewhere()) {
            open(entry);
            return;
        }
        setFocused(entry.entry().id().equals(focused) ? null : entry.entry().id());
    }

    private void openStarred(Located entry) {
        if (entry.entry().folder()) {
            final var folderPath = append(entry.location(), entry.entry().name());
            navigate(entry.rootId(), folderPath, null);
        } else {
            navigate(entry.rootId(), entry.location(), entry.entry().id());
        }
    }

    @Override
    public void contextMenu(Located entry, MenuPosition position) {
        entryMenu.open(entry, position, starredView, starred(entry), readOnly(), this::handleAction);
    }

    @Override
    public void toggleStar(Located entry) {
        final var on = starred(entry);
        workspace.setStarred(entry.rootId(), entry.entry().id(), !on);
        Toasts.show(on ? "Removed “" + entry.entry().name() + "” from starred" : "Added “" + entry.entry().name() + "” to starred", "star");
        refresh();
    }

    @Override
    public Sort sort() {
        return workspace.sort();
    }

    @Override
    public void sortBy(SortKey key) {
        final var newSort = workspace.sort().toggle(key);
        workspace.setSort(newSort);
        refresh();
    }

    private void onQuery(String value) {
        if (resetting) {
            return;
        }
        query = value;
        refresh();
    }

    private void clearSelection() {
        selected.clear();
        refresh();
    }

    // ---- actions --------------------------------------------------------------------------

    private void handleAction(EntryAction action, Located entry) {
        final var targets = entry != null ? List.of(entry)
                : displayEntries().stream().filter(this::selected).toList();
        if (targets.isEmpty()) {
            return;
        }
        if (readOnly() && action.writes()) {
            Toasts.readOnly();
            return;
        }
        final var first = targets.getFirst();
        switch (action) {
            case OPEN -> open(first);
            case DOWNLOAD -> Toasts.show(targets.size() > 1 ? "Downloading " + targets.size() + " items…"
                    : "Downloading “" + first.entry().name() + "”…", "download");
            case RENAME -> {
                final var dialog = NameDialog.rename(first.entry().name(), first.entry().folder(), name -> rename(first, name));
                dialog.open();
            }
            case MOVE -> {
                final var entries = targets.stream().map(Located::entry).toList();
                final var tree = workspace.tree(root.id());
                final var from = folderOf(first);
                final var dialog = new MoveDialog(entries, root, tree, from, dest -> move(targets, dest));
                dialog.open();
            }
            case DELETE -> {
                final var entries = targets.stream().map(Located::entry).toList();
                final var dialog = new DeleteDialog(entries, () -> delete(targets));
                dialog.open();
            }
            case COPY -> duplicate(targets);
            case STAR -> {
                if (targets.size() == 1) {
                    toggleStar(first);
                } else {
                    targets.forEach(t -> workspace.setStarred(t.rootId(), t.entry().id(), true));
                    Toasts.show("Added " + targets.size() + " items to starred", "star");
                    refresh();
                }
            }
        }
    }

    private void rename(Located target, String name) {
        final var folder = folderOf(target);
        workspace.rename(root.id(), folder, target.entry().id(), name);
        Toasts.show("Renamed to “" + name + "”", "edit");
        refresh();
    }

    private Map<List<String>, List<String>> idsByFolder(List<Located> targets) {
        final var groups = new LinkedHashMap<List<String>, List<String>>();
        for (final var t : targets) {
            final var folder = folderOf(t);
            groups.computeIfAbsent(folder, k -> new ArrayList<>()).add(t.entry().id());
        }
        return groups;
    }

    private void move(List<Located> targets, List<String> dest) {
        idsByFolder(targets).forEach((from, ids) -> workspace.move(root.id(), from, ids, dest));
        selected.clear();
        final var destName = dest.isEmpty() ? root.name() : dest.getLast();
        final var movedLabel = label(targets);
        Toasts.show("Moved " + movedLabel + " to “" + destName + "”", "move");
        refresh();
    }

    private void delete(List<Located> targets) {
        idsByFolder(targets).forEach((from, ids) -> workspace.delete(root.id(), from, ids));
        targets.forEach(t -> workspace.setStarred(t.rootId(), t.entry().id(), false));
        selected.clear();
        if (targets.stream().anyMatch(this::focused)) {
            focused = null;
        }
        final var deletedLabel = label(targets);
        Toasts.show(deletedLabel + " deleted", "trash");
        refresh();
    }

    private void duplicate(List<Located> targets) {
        for (final var t : targets) {
            final var folder = folderOf(t);
            workspace.duplicate(root.id(), folder, t.entry().id());
        }
        final var duplicatedLabel = label(targets);
        Toasts.show("Duplicated " + duplicatedLabel, "copy");
        refresh();
    }

    private static String label(List<Located> targets) {
        return targets.size() > 1 ? targets.size() + " items" : "“" + targets.getFirst().entry().name() + "”";
    }

    private void newFolder() {
        if (readOnly()) {
            Toasts.readOnly();
            return;
        }
        final var dialog = NameDialog.newFolder(name -> {
            workspace.createFolder(root.id(), path, name);
            Toasts.show("Folder “" + name + "” created", "folder");
            refresh();
        });
        dialog.open();
    }

    // ---- uploads --------------------------------------------------------------------------

    private void openUploadDialog() {
        if (readOnly()) {
            Toasts.readOnly();
            return;
        }
        final var dialog = new UploadDialog(workspace.catalog(), this::startUploads);
        dialog.open();
    }

    private void onFilesDropped(List<IncomingFile> files) {
        if (starredView) {
            return;
        }
        if (readOnly()) {
            Toasts.readOnly();
            return;
        }
        startUploads(files);
    }

    private void startUploads(List<IncomingFile> files) {
        final var destination = path;
        final var existing = workspace.fileNames(root.id(), destination);
        final var conflicts = files.stream().filter(f -> existing.contains(f.name())).toList();
        if (conflicts.isEmpty()) {
            enqueue(destination, files.stream().map(f -> new PlannedUpload(f, f.name(), false)).toList());
            return;
        }
        final var dialog = new ConflictDialog(workspace.catalog(), conflicts, files.size(), existing,
                choices -> resolveConflicts(files, destination, existing, choices));
        dialog.open();
    }

    private void resolveConflicts(List<IncomingFile> files, List<String> destination, Set<String> existing,
                                  Map<String, ConflictChoice> choices) {
        final var taken = new HashSet<>(existing);
        final var planned = new ArrayList<PlannedUpload>();
        for (final var f : files) {
            final var choice = choices.get(f.name());
            if (choice == null) {
                final var upload = new PlannedUpload(f, f.name(), false);
                planned.add(upload);
            } else if (choice == ConflictChoice.REPLACE) {
                final var upload = new PlannedUpload(f, f.name(), true);
                planned.add(upload);
            } else if (choice == ConflictChoice.KEEP_BOTH) {
                final var renamed = Workspace.keepBothName(f.name(), taken);
                taken.add(renamed);
                final var upload = new PlannedUpload(f, renamed, false);
                planned.add(upload);
            }
        }
        final var skipped = files.size() - planned.size();
        if (planned.isEmpty() && skipped > 0) {
            Toasts.show(skipped + " file" + (skipped > 1 ? "s" : "") + " skipped", "info");
            return;
        }
        enqueue(destination, planned);
    }

    private void enqueue(List<String> destination, List<PlannedUpload> planned) {
        workspace.enqueue(root.id(), destination, planned);
        getUI().ifPresent(ui -> ui.setPollInterval(UPLOAD_POLL_MS));
        refresh();
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        final var ui = attachEvent.getUI();
        pollRegistration = ui.addPollListener(e -> advanceUploads(ui));
        if (workspace.uploading()) {
            ui.setPollInterval(UPLOAD_POLL_MS);
        }
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (pollRegistration != null) {
            pollRegistration.remove();
            pollRegistration = null;
        }
        detachEvent.getUI().setPollInterval(-1);
        super.onDetach(detachEvent);
    }

    private void advanceUploads(UI ui) {
        if (!workspace.uploading()) {
            ui.setPollInterval(-1);
            return;
        }
        final var finished = workspace.advanceUploads();
        finished.forEach(t -> Toasts.show((t.replace() ? "Replaced “" : "Uploaded “") + t.name() + "”", "check"));
        if (!workspace.uploading()) {
            ui.setPollInterval(-1);
        }
        refresh();
    }

    private static List<String> append(List<String> base, String name) {
        final var out = new ArrayList<>(base);
        out.add(name);
        return List.copyOf(out);
    }
}
