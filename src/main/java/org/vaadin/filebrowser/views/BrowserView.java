package org.vaadin.filebrowser.views;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.vaadin.filebrowser.config.FileBrowserProperties;
import org.vaadin.filebrowser.data.ArchiveFormat;
import org.vaadin.filebrowser.data.AutoRefresh;
import org.vaadin.filebrowser.data.Formats;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.Incoming;
import org.vaadin.filebrowser.data.IncomingFile;
import org.vaadin.filebrowser.data.Located;
import org.vaadin.filebrowser.data.MoveCheck;
import org.vaadin.filebrowser.data.Sort;
import org.vaadin.filebrowser.data.SortKey;
import org.vaadin.filebrowser.service.ArchiveExtractor;
import org.vaadin.filebrowser.service.CurrentUser;
import org.vaadin.filebrowser.service.Downloads;
import org.vaadin.filebrowser.service.UploadQueue.Planned;
import org.vaadin.filebrowser.service.TransferTask;
import org.vaadin.filebrowser.service.UploadQueue;
import org.vaadin.filebrowser.service.Workspace.ConflictChoice;
import org.vaadin.filebrowser.service.Workspace.Density;
import org.vaadin.filebrowser.service.Workspace.Layout;
import org.vaadin.filebrowser.service.Workspace;
import org.vaadin.filebrowser.storage.StorageEntry;
import org.vaadin.filebrowser.storage.StorageException;
import org.vaadin.filebrowser.storage.StoragePaths;
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
import org.vaadin.filebrowser.ui.Lightbox;
import org.vaadin.filebrowser.ui.RootChip;
import org.vaadin.filebrowser.ui.Segmented;
import org.vaadin.filebrowser.ui.Sidebar;
import org.vaadin.filebrowser.ui.Notices;
import org.vaadin.filebrowser.ui.NotificationBell;
import org.vaadin.filebrowser.ui.Toolbar;
import org.vaadin.filebrowser.ui.Ui;
import org.vaadin.filebrowser.ui.dialog.ConflictDialog;
import org.vaadin.filebrowser.ui.dialog.DeleteDialog;
import org.vaadin.filebrowser.ui.dialog.DestinationDialog;
import org.vaadin.filebrowser.ui.dialog.DestinationDialog.Mode;
import org.vaadin.filebrowser.ui.dialog.MoveConfirmDialog;
import org.vaadin.filebrowser.ui.dialog.NameDialog;
import org.vaadin.filebrowser.ui.dialog.UploadDialog;

import jakarta.annotation.security.PermitAll;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.breadcrumbs.Breadcrumbs;
import com.vaadin.flow.component.breadcrumbs.BreadcrumbsItem;
import com.vaadin.flow.component.grid.contextmenu.GridContextMenu;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.popover.PopoverPosition;
import com.vaadin.flow.dom.Style;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.shared.Registration;

/** Browses one file system: sidebar tree, header with breadcrumb and toolbar, list/grid, details. */
// split up with #77
@SuppressWarnings("PMD.TooManyMethods")
@PermitAll
@Route("fs/:rootId/:path*")
public class BrowserView extends Div implements BeforeEnterObserver, HasDynamicTitle, EntryHandler {

    private static final String ROOT_PARAM = "rootId";
    private static final String PATH_PARAM = "path";
    private static final String FOCUS_PARAM = "focus";
    /** Poll ticks don't land exactly on the interval; one this close to it counts as due. */
    private static final Duration AUTO_REFRESH_SLACK = Duration.ofSeconds(1);

    private final Workspace workspace;
    private final long maxUploadBytes;
    private final int maxFolderUploadEntries;
    private final ArchiveExtractor.Limits extractLimits;
    private final CurrentUser currentUser;

    private final Div sidebarSlot = Ui.div("fb-sidebar-slot");
    private final FileDropArea main;
    private final Div titleRow = Ui.div("fb-header-row");
    private final Span footerCount = Ui.span("fb-footer-count", "");
    private final Toolbar toolbar;
    private final Div content = Ui.div("fb-content");
    private final Div detailsSlot = Ui.div("fb-details-slot");
    private final Div bulkSlot = Ui.div("fb-bulk-slot");
    private final EntryMenu entryMenu = new EntryMenu();
    private final Anchor downloadLink = new Anchor();

    private FsRoot root;
    private List<String> path = List.of();
    private Sidebar sidebar;
    private FileListView listView;
    private FileGridView gridView;
    private String query = "";
    private List<StorageEntry> currentEntries = List.of();
    private String loadError;
    private final Set<String> selectedPaths = new HashSet<>();
    private String focusedPath;
    private boolean starredView;
    /** Between a drag start and its end; drop targets are active and auto refresh waits meanwhile. */
    private boolean dragging;
    /** What a drag carries when its drop target gets no server-side data, as for a row dragged out of the list. */
    private Located draggedEntry;
    /** Which folder, search and density the list was built for; while it stays the same the list is updated in place. */
    private String listViewKey;
    private boolean resetting;
    private Registration pollRegistration;
    private Registration taskRegistration;
    /** Set while attached. */
    private MainLayout layout;
    private AutoRefresh autoRefresh = AutoRefresh.OFF;
    private Instant lastLoad = Instant.MIN;
    /** What the last render showed, so an auto refresh that finds no change renders nothing. */
    private List<String> shownSignature = List.of();

    public BrowserView(Workspace workspace, CurrentUser currentUser, FileBrowserProperties properties) {
        this.workspace = workspace;
        this.currentUser = currentUser;
        this.maxUploadBytes = properties.maxUploadSize().toBytes();
        this.maxFolderUploadEntries = properties.maxFolderUploadEntries();
        this.extractLimits = new ArchiveExtractor.Limits(properties.maxExtractBytes(), properties.maxExtractEntries());
        setClassName("fb-browser");

        final var toolbarCallbacks = new Toolbar.Callbacks(this::onQuery, this::sortBy, this::setAutoRefresh,
                this::toggleDensity, this::setLayout, this::newFolder, this::openUploadDialog);
        toolbar = new Toolbar(toolbarCallbacks);

        main = new FileDropArea(workspace.getCatalog(), false, maxFolderUploadEntries);
        main.addClassName("fb-main");
        main.setOnFiles(this::onFilesDropped);
        final var uploadIcon = new Icon("upload");
        final var dropIcon = Ui.span("fb-drop-overlay-icon", uploadIcon);
        final var dropTitle = Ui.span("fb-drop-overlay-title", "Drop files or folders to upload");
        final var dropSub = Ui.span("fb-drop-overlay-sub", "They'll be added to this folder");
        final var dropOverlay = Ui.div("fb-drop-overlay", dropIcon, dropTitle, dropSub);
        final var header = Ui.div("fb-header", titleRow, toolbar);
        final var body = Ui.div("fb-body", content, detailsSlot);
        final var footerSpacer = Ui.spacer();
        final var bell = new NotificationBell(PopoverPosition.TOP_END);
        final var footer = Ui.div("fb-footer", footerCount, footerSpacer, bell);
        main.add(header, body, footer, dropOverlay, bulkSlot);

        downloadLink.getElement().setAttribute("download", true);
        downloadLink.getStyle().setDisplay(Style.Display.NONE);
        add(sidebarSlot, main, entryMenu, downloadLink);
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
        // an unreadable root is shown with its error; only a missing subfolder sends you back to the root
        if (!newPath.isEmpty() && !workspace.isFolder(target.orElseThrow(), newPath)) {
            final var rootParams = params(target.orElseThrow().id(), List.of());
            event.forwardTo(BrowserView.class, rootParams);
            return;
        }
        final var rootChanged = root == null || !root.id().equals(target.orElseThrow().id());
        root = target.orElseThrow();
        path = List.copyOf(newPath);
        if (rootChanged) {
            // auto refresh is chosen per file system and never carried over
            autoRefresh = AutoRefresh.OFF;
            updatePollInterval();
            final var sidebarCallbacks = new Sidebar.Callbacks(this::navTo, this::openRoot, this::backToRoots,
                    this::showStarred, this::logout, this::dropInto);
            final var browsable = workspace.browsableRoots();
            final var sidebarRoot = root;
            final var user = currentUser.get().orElse(null);
            sidebar = new Sidebar(root, browsable, folder -> workspace.subfolders(sidebarRoot, folder), workspace.getCatalog(),
                    user, sidebarCallbacks);
            sidebarSlot.removeAll();
            sidebarSlot.add(sidebar);
        }
        resetView();
        focusedPath = event.getLocation().getQueryParameters().getSingleParameter(FOCUS_PARAM).orElse(null);
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

    private void setAutoRefresh(AutoRefresh option) {
        autoRefresh = option;
        updatePollInterval();
        refresh();
    }

    private void toggleDensity() {
        workspace.setDensity(workspace.getDensity() == Density.DENSE ? Density.COMFORTABLE : Density.DENSE);
        refresh();
    }

    private void setLayout(Layout layout) {
        workspace.setLayout(layout);
        refresh();
    }

    private void backToRoots() {
        UI.getCurrent().navigate(RootsView.class);
    }

    private void logout() {
        currentUser.signOut();
    }

    // the search field's listener reads it while clearSearch() runs
    @SuppressWarnings("PMD.UnusedAssignment")
    private void resetView() {
        selectedPaths.clear();
        focusedPath = null;
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

    /** Reads the open folder from storage; called once per refresh. */
    private void loadFolder() {
        lastLoad = Instant.now();
        try {
            root = workspace.current(root);
            currentEntries = workspace.list(root, path);
            loadError = null;
        } catch (StorageException e) {
            currentEntries = List.of();
            loadError = e.getMessage();
        }
    }

    private List<Located> displayEntries() {
        if (starredView) {
            return workspace.starredIn(root);
        }
        final var term = query.trim().toLowerCase(Locale.ROOT);
        final var filtered = currentEntries.stream()
                .filter(e -> term.isEmpty() || e.name().toLowerCase(Locale.ROOT).contains(term))
                .map(e -> Located.here(e, root.id()))
                .toList();
        return workspace.getSort().apply(filtered);
    }

    private StorageEntry focusedEntry() {
        if (focusedPath == null) {
            return null;
        }
        return currentEntries.stream().filter(e -> e.path().equals(focusedPath)).findFirst().orElse(null);
    }

    /** The folder an entry lives in: its own location for starred items. */
    private List<String> folderOf(Located located) {
        return located.elsewhere() ? located.location() : path;
    }

    // ---- rendering ------------------------------------------------------------------------

    private void refresh() {
        loadFolder();
        render();
    }

    private static List<String> signature(List<Located> entries, String error) {
        final var out = new ArrayList<String>();
        out.add(String.valueOf(error));
        entries.forEach(l -> out.add(l.rootId() + ":" + l.entry().path() + "|" + l.entry().size() + "|"
                + l.entry().modified()));
        return out;
    }

    /** A poll tick with auto refresh on: re-read, and render only if the folder changed. */
    private void autoRefreshTick() {
        if (dragging || !autoRefresh.on() || Duration.between(lastLoad, Instant.now()).plus(AUTO_REFRESH_SLACK)
                .compareTo(autoRefresh.getInterval()) < 0) {
            return;
        }
        loadFolder();
        final var entries = displayEntries();
        if (signature(entries, loadError).equals(shownSignature)) {
            return;
        }
        final var present = new HashSet<String>();
        entries.forEach(l -> present.add(l.entry().path()));
        selectedPaths.retainAll(present);
        if (focusedPath != null && !present.contains(focusedPath)) {
            focusedPath = null;
        }
        render();
    }

    private void render() {
        // rows are rebuilt with their drop targets off; a dragged row that is replaced never reports its drag end
        dragging = false;
        final var entries = displayEntries();
        shownSignature = signature(entries, loadError);
        final var starredCount = workspace.starredIn(root).size();
        sidebar.update(path, starredView, starredCount);
        renderTitleRow(entries.size());
        main.getElement().getClassList().set("fb-main--starred", starredView);
        toolbar.setVisible(!starredView);
        if (!starredView) {
            final var bulk = selectedPaths.isEmpty() ? null
                    : new HeaderBulkActions(selectedPaths.size(), readOnly(), a -> handleAction(a, null), this::clearSelection);
            final var toolbarState = new Toolbar.State(workspace.getSort(), autoRefresh, workspace.getDensity(),
                    workspace.getLayout(), readOnly(), bulk);
            toolbar.render(toolbarState);
        }
        renderContent(entries);
        renderDetails();
        bulkSlot.removeAll();
        if (!starredView && !selectedPaths.isEmpty()) {
            final var bulkBar = new BulkBar(selectedPaths.size(), readOnly(), a -> handleAction(a, null), this::clearSelection);
            bulkSlot.add(bulkBar);
        }
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
                final var readOnlyBadge = Ui.dotBadge("Read only", BadgeVariant.WARNING);
                titleRow.add(readOnlyBadge);
            }
        }
        final String noun = starredView ? "starred item" : "item";
        footerCount.setText(count + " " + noun + (count == 1 ? "" : "s"));
        if (starredView) {
            final var spacer = Ui.spacer();
            titleRow.add(spacer);
            final var listOption = Segmented.Option.iconOnly(Layout.LIST, "list", "List");
            final var gridOption = Segmented.Option.iconOnly(Layout.GRID, "grid", "Grid");
            final var layoutToggle = new Segmented<>("fb-seg--view", List.of(listOption, gridOption), workspace.getLayout(),
                    this::setLayout);
            titleRow.add(layoutToggle);
        }
    }

    private Breadcrumbs breadcrumb() {
        final var crumbs = new Breadcrumbs(Breadcrumbs.Mode.MANUAL);
        crumbs.addClassName("fb-breadcrumb");
        final var rootItem = crumb(root.name(), List.of());
        final var rootChip = RootChip.of(root, workspace.getCatalog(), RootChip.Size.XS);
        rootItem.setPrefixComponent(rootChip);
        crumbs.add(rootItem);
        for (int i = 0; i < path.size(); i++) {
            final var target = List.copyOf(path.subList(0, i + 1));
            final var item = crumb(path.get(i), target);
            crumbs.add(item);
        }
        return crumbs;
    }

    /** The current folder is the last item and is not a link. */
    private BreadcrumbsItem crumb(String name, List<String> target) {
        if (target.equals(path)) {
            return new BreadcrumbsItem(name);
        }
        final var params = params(root.id(), target);
        return new BreadcrumbsItem(name, BrowserView.class, params);
    }

    private void renderContent(List<Located> entries) {
        final var key = String.join("|", root.id(), String.join("/", path), query, workspace.getDensity().name());
        if (canUpdateList(entries, key)) {
            listView.update(entries);
            return;
        }
        content.removeAll();
        listView = null;
        gridView = null;
        if (loadError != null && !starredView) {
            final var unavailable = EmptyState.unavailable(loadError);
            content.add(unavailable);
            return;
        }
        if (entries.isEmpty()) {
            final EmptyState.Kind kind = starredView ? EmptyState.Kind.STARRED_ROOT
                    : query.isBlank() ? EmptyState.Kind.FOLDER : EmptyState.Kind.SEARCH;
            final var canUpload = kind == EmptyState.Kind.FOLDER && !readOnly();
            final var emptyState = new EmptyState(kind, canUpload ? this::openUploadDialog : null);
            content.add(emptyState);
            return;
        }
        if (workspace.getLayout() == Layout.LIST) {
            listView = new FileListView(entries, this, workspace.getDensity(), workspace.getCatalog());
            listViewKey = key;
            content.add(listView);
        } else {
            gridView = new FileGridView(entries, this, workspace.getCatalog());
            content.add(gridView);
        }
    }

    /** Whether the list on show is for the same folder, search and density, so it can be updated in place. */
    private boolean canUpdateList(List<Located> entries, String key) {
        return listView != null && workspace.getLayout() == Layout.LIST && loadError == null && !entries.isEmpty()
                && key.equals(listViewKey);
    }

    private void renderDetails() {
        detailsSlot.removeAll();
        final var entry = focusedEntry();
        if (entry != null && !starredView) {
            final var located = Located.here(entry, root.id());
            final var starred = workspace.isStarred(root.id(), entry.path());
            final var details = new DetailsPanel(entry, root, readOnly(), starred, workspace.getCatalog(),
                    () -> setFocused(null), a -> handleAction(a, located));
            detailsSlot.add(details);
        }
    }

    private void setFocused(String id) {
        focusedPath = id;
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
        return selectedPaths.contains(entry.entry().path());
    }

    @Override
    public boolean focused(Located entry) {
        return entry.entry().path().equals(focusedPath);
    }

    @Override
    public boolean starred(Located entry) {
        return workspace.isStarred(entry.rootId(), entry.entry().path());
    }

    @Override
    public void toggleSelected(Located entry) {
        if (!selectedPaths.remove(entry.entry().path())) {
            selectedPaths.add(entry.entry().path());
        }
        refresh();
    }

    @Override
    public void toggleAll(boolean on) {
        selectedPaths.clear();
        if (on) {
            displayEntries().forEach(e -> selectedPaths.add(e.entry().path()));
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
            navigate(root.id(), entry.location(), entryValue.path());
        } else {
            preview(entryValue);
        }
    }

    private void preview(StorageEntry file) {
        try {
            root = workspace.current(root);
        } catch (StorageException e) {
            Notices.error(e.getMessage());
            return;
        }
        if (!root.options().opening()) {
            Notices.error("Opening files is turned off for “" + root.name() + "”");
            return;
        }
        final var shown = displayEntries().stream().map(Located::entry).toList();
        final var files = Lightbox.viewable(shown);
        final var lightbox = new Lightbox(workspace.getCatalog(), root.id(), files, file, f -> setFocused(f.path()),
                f -> download(List.of(Located.here(f, root.id()))));
        lightbox.open();
    }

    @Override
    public boolean draggable() {
        return !starredView && !readOnly();
    }

    @Override
    public void dragStarted(Located dragged) {
        draggedEntry = dragged;
        dragging = true;
        setDropTargetsActive(true);
    }

    @Override
    public void dragEnded() {
        dragging = false;
        setDropTargetsActive(false);
    }

    @Override
    public void dropOnto(Located dragged, Located folder) {
        final var folderPath = append(folderOf(folder), folder.entry().name());
        dropInto(dragged, folderPath);
    }

    @Override
    public void selectRow(Located entry) {
        // the details panel shows entries of the open folder only
        if (starredView || entry.elsewhere()) {
            return;
        }
        setFocused(entry.entry().path().equals(focusedPath) ? null : entry.entry().path());
    }

    private void openStarred(Located entry) {
        if (entry.entry().folder()) {
            final var folderPath = append(entry.location(), entry.entry().name());
            navigate(entry.rootId(), folderPath, null);
        } else {
            navigate(entry.rootId(), entry.location(), entry.entry().path());
        }
    }

    @Override
    public void contextMenu(Located entry, Component anchor) {
        entryMenu.open(entry, anchor, starredView, starred(entry), readOnly(), root.options().opening(),
                this::handleAction);
    }

    @Override
    public void fillContextMenu(GridContextMenu<Located> menu, Located entry) {
        EntryMenu.fill(menu, entry, starredView, starred(entry), readOnly(), root.options().opening(), this::handleAction);
    }

    @Override
    public void toggleStar(Located entry) {
        final var on = starred(entry);
        workspace.setStarred(entry.rootId(), entry.entry().path(), !on);
        Notices.show(on ? "Removed “" + entry.entry().name() + "” from starred" : "Added “" + entry.entry().name() + "” to starred", "star");
        refresh();
    }

    @Override
    public Sort sort() {
        return workspace.getSort();
    }

    @Override
    public void sortBy(SortKey key) {
        final var newSort = workspace.getSort().toggle(key);
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
        selectedPaths.clear();
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
            Notices.readOnly();
            return;
        }
        final var first = targets.getFirst();
        switch (action) {
            case OPEN -> open(first);
            case DOWNLOAD -> download(targets);
            case RENAME -> {
                final var dialog = NameDialog.rename(first.entry().name(), first.entry().folder(), name -> rename(first, name));
                dialog.open();
            }
            case MOVE -> pickDestination(Mode.MOVE, targets);
            case COPY -> pickDestination(Mode.COPY, targets);
            case DELETE -> {
                final var entries = targets.stream().map(Located::entry).toList();
                final var delay = workspace.deletesUndoable() ? workspace.deleteDelay() : null;
                final var dialog = new DeleteDialog(entries, delay, () -> delete(targets));
                dialog.open();
            }
            case STAR -> {
                if (targets.size() == 1) {
                    toggleStar(first);
                } else {
                    targets.forEach(t -> workspace.setStarred(t.rootId(), t.entry().path(), true));
                    Notices.show("Added " + targets.size() + " items to starred", "star");
                    refresh();
                }
            }
        }
    }

    private void rename(Located target, String name) {
        try {
            workspace.rename(root, target.entry(), name);
            Notices.show("Renamed to “" + name + "”", "edit");
        } catch (StorageException e) {
            Notices.error(e.getMessage());
        }
        afterChange();
    }

    /** Applies {@code change} to each target, stopping at the first failure; returns how many succeeded. */
    private int forEachTarget(List<Located> targets, Consumer<StorageEntry> change) {
        int done = 0;
        for (final var t : targets) {
            try {
                change.accept(t.entry());
                done++;
            } catch (StorageException e) {
                Notices.error(e.getMessage());
                break;
            }
        }
        return done;
    }

    private void pickDestination(Mode mode, List<Located> targets) {
        final var fileSystems = workspace.transferTargets(root);
        if (fileSystems.isEmpty()) {
            Notices.readOnly();
            return;
        }
        final var entries = targets.stream().map(Located::entry).toList();
        final var from = folderOf(targets.getFirst());
        final var dialog = new DestinationDialog(mode, entries, root, fileSystems, workspace.getCatalog(),
                workspace::subfolders, from, (destRoot, dest) -> checkClashes(mode, targets, destRoot, dest, false));
        dialog.open();
    }

    private static boolean inFolder(StorageEntry entry, List<String> folder) {
        return entry.parent().equals(folder);
    }

    /**
     * Asks what to do with names that already exist in {@code dest}, then moves or copies. With {@code confirm}, a
     * move without clashes is confirmed first; the clash dialog serves as the confirmation otherwise. Items copied
     * into their own folder never clash: they become "name copy".
     */
    private void checkClashes(Mode mode, List<Located> targets, FsRoot destRoot, List<String> dest, boolean confirm) {
        final List<StorageEntry> existing;
        final var sameRoot = destRoot.id().equals(root.id());
        try {
            existing = workspace.list(destRoot, dest);
        } catch (StorageException e) {
            Notices.error(e.getMessage());
            return;
        }
        final var existingFolders = new HashMap<String, Boolean>();
        existing.forEach(e -> existingFolders.put(e.name(), e.folder()));
        final var clashes = targets.stream()
                .map(Located::entry)
                .filter(e -> mode == Mode.MOVE || !sameRoot || !inFolder(e, dest))
                .filter(e -> existingFolders.containsKey(e.name()))
                .map(e -> new ConflictDialog.Clash(e.name(), e.type(), e.folder() || existingFolders.get(e.name())))
                .toList();
        final var names = Set.copyOf(existingFolders.keySet());
        if (clashes.isEmpty() && confirm) {
            final var entries = targets.stream().map(Located::entry).toList();
            final var confirmDialog = new MoveConfirmDialog(entries, folderName(dest),
                    () -> transfer(mode, targets, destRoot, dest, names, Map.of()));
            confirmDialog.open();
            return;
        }
        if (clashes.isEmpty()) {
            transfer(mode, targets, destRoot, dest, names, Map.of());
            return;
        }
        final var purpose = mode == Mode.MOVE ? ConflictDialog.Purpose.MOVE : ConflictDialog.Purpose.COPY;
        final var dialog = new ConflictDialog(purpose, workspace.getCatalog(), clashes, targets.size(),
                names, choices -> transfer(mode, targets, destRoot, dest, names, choices));
        dialog.open();
    }

    private String folderName(List<String> folder) {
        return folder.isEmpty() ? root.name() : folder.getLast();
    }

    /** {@code dragged} was dropped onto a folder in the list, the grid or the tree. */
    private void dropInto(Located dropped, List<String> dest) {
        setDropTargetsActive(false);
        dragging = false;
        final var dragged = dropped != null ? dropped : draggedEntry;
        draggedEntry = null;
        if (!draggable() || dragged == null) {
            return;
        }
        final var targets = selected(dragged)
                ? displayEntries().stream().filter(this::selected).toList()
                : List.of(dragged);
        final var destPath = StoragePaths.join(dest);
        // dropping a folder onto itself is the usual slip at the end of a drag, not a mistake worth a message
        if (targets.stream().anyMatch(t -> t.entry().path().equals(destPath))) {
            return;
        }
        final var movedFolders = targets.stream().map(Located::entry).filter(StorageEntry::folder).map(StorageEntry::path).toList();
        switch (MoveCheck.of(path, movedFolders, dest)) {
            case SAME_FOLDER -> { }
            case INTO_ITSELF -> Notices.error("Can't move a folder into itself.");
            case OK -> checkClashes(Mode.MOVE, targets, root, dest, true);
        }
    }

    private void setDropTargetsActive(boolean on) {
        if (listView != null) {
            listView.setDropActive(on);
        }
        if (gridView != null) {
            gridView.setDropActive(on);
        }
        sidebar.setDropActive(on);
    }

    /** Queues the move or copy with the names and Replace choices settled now; the queue reports the outcome. */
    private void transfer(Mode mode, List<Located> targets, FsRoot destRoot, List<String> dest, Set<String> existing,
                          Map<String, ConflictChoice> choices) {
        final var copyInPlace = mode == Mode.COPY && destRoot.id().equals(root.id());
        final var items = transferItems(targets, copyInPlace, dest, existing, choices);
        if (mode == Mode.MOVE) {
            selectedPaths.clear();
        }
        if (items.isEmpty()) {
            refresh();
            return;
        }
        final var kind = mode == Mode.MOVE ? TransferTask.Kind.MOVE : TransferTask.Kind.COPY;
        try {
            workspace.transfer(kind, root, destRoot, dest, items);
        } catch (StorageException e) {
            Notices.error(e.getMessage());
        }
        refresh();
        tasksChanged();
    }

    /**
     * What to move or copy, under which names; skipped items are left out.
     *
     * @param copyInPlace a copy within the same file system, where an item copied into its own folder is duplicated
     */
    private static List<TransferTask.Item> transferItems(List<Located> targets, boolean copyInPlace, List<String> dest,
                                                         Set<String> existing, Map<String, ConflictChoice> choices) {
        final var taken = new HashSet<>(existing);
        final var items = new ArrayList<TransferTask.Item>();
        for (final var target : targets) {
            final var entry = target.entry();
            if (copyInPlace && inFolder(entry, dest)) {
                items.add(new TransferTask.Item(entry, entry.name(), false, true));
                continue;
            }
            final var choice = choices.get(entry.name());
            if (choice == ConflictChoice.SKIP) {
                continue;
            }
            final var finalName = choice == ConflictChoice.KEEP_BOTH ? Workspace.keepBothName(entry.name(), taken)
                    : entry.name();
            taken.add(finalName);
            items.add(new TransferTask.Item(entry, finalName, choice == ConflictChoice.REPLACE, false));
        }
        return items;
    }

    private void delete(List<Located> targets) {
        if (workspace.deletesUndoable()) {
            deleteLater(targets);
            return;
        }
        final var done = forEachTarget(targets, e -> workspace.delete(root, e));
        selectedPaths.clear();
        if (targets.stream().anyMatch(this::focused)) {
            focusedPath = null;
        }
        if (done > 0) {
            final var deletedLabel = done == targets.size() ? label(targets) : Formats.plural(done, "item");
            Notices.show(deletedLabel + " deleted", "trash");
        }
        afterChange();
    }

    /** Hides the targets now; the delete runs on the server once the toast's Undo has expired. */
    private void deleteLater(List<Located> targets) {
        final var entries = targets.stream().map(Located::entry).toList();
        try {
            final var pending = workspace.deleteLater(root, entries);
            final var label = pending.label();
            Notices.undoable("Deleted " + label, "trash", workspace.deleteDelay(), () -> {
                if (workspace.undoDelete(pending)) {
                    Notices.show("Restored " + label, "refresh");
                    tasksChanged();
                    afterChange();
                } else {
                    Notices.error("Too late to undo: " + label + " is already being deleted.");
                }
            });
        } catch (StorageException e) {
            Notices.error(e.getMessage());
            return;
        }
        selectedPaths.clear();
        if (targets.stream().anyMatch(this::focused)) {
            focusedPath = null;
        }
        tasksChanged();
        afterChange();
    }

    private void afterChange() {
        sidebar.reloadFolders();
        refresh();
    }

    private static String label(List<Located> targets) {
        return targets.size() > 1 ? targets.size() + " items" : "“" + targets.getFirst().entry().name() + "”";
    }

    private void newFolder() {
        if (readOnly()) {
            Notices.readOnly();
            return;
        }
        final var dialog = NameDialog.newFolder(name -> {
            try {
                workspace.createFolder(root, path, name);
                Notices.show("Folder “" + name + "” created", "folder");
            } catch (StorageException e) {
                Notices.error(e.getMessage());
            }
            afterChange();
        });
        dialog.open();
    }

    // ---- uploads --------------------------------------------------------------------------

    private void openUploadDialog() {
        if (readOnly()) {
            Notices.readOnly();
            return;
        }
        final var now = workspace.current(root);
        final var dialog = new UploadDialog(workspace.getCatalog(), now.options(), maxUploadBytes, maxFolderUploadEntries,
                url -> workspace.inspectLink(now, url), this::startUploads);
        dialog.open();
    }

    private void onFilesDropped(Incoming files) {
        if (starredView) {
            return;
        }
        if (readOnly()) {
            Notices.readOnly();
            return;
        }
        startUploads(files);
    }

    private void startUploads(Incoming picked) {
        final var incoming = withinLimit(picked);
        if (incoming.isEmpty()) {
            return;
        }
        final var files = incoming.files();
        final var destination = path;
        final Set<String> existingFiles;
        final Set<String> existing;
        try {
            existingFiles = workspace.fileNames(root, destination);
            existing = workspace.entryNames(root, destination);
        } catch (StorageException e) {
            Notices.error(e.getMessage());
            return;
        }
        final var conflicts = uploadClashes(files, existingFiles, existing);
        incoming.folders().stream()
                .filter(f -> existing.contains(f.name()))
                .forEach(f -> conflicts.add(new ConflictDialog.Clash(f.name(), StorageEntry.FOLDER_TYPE, true)));
        if (conflicts.isEmpty()) {
            resolveConflicts(incoming, destination, existing, Map.of());
            return;
        }
        final var dialog = new ConflictDialog(ConflictDialog.Purpose.UPLOAD, workspace.getCatalog(), conflicts,
                incoming.itemCount(), existing, choices -> resolveConflicts(incoming, destination, existing, choices));
        dialog.open();
    }

    /** The files within the upload limit, in folders too; the rest are reported and dropped. */
    private Incoming withinLimit(Incoming picked) {
        final var tooLarge = picked.filesOver(maxUploadBytes);
        if (!tooLarge.isEmpty()) {
            final var limit = Formats.size(maxUploadBytes);
            final var names = tooLarge.size() == 1 ? "“" + tooLarge.getFirst().name() + "” is" : tooLarge.size() + " files are";
            Notices.error(names + " larger than " + limit + " and won't be uploaded");
        }
        return picked.withoutFilesOver(maxUploadBytes);
    }

    /** A stored file clashes with a file of the same name; a new folder for an archive with anything. */
    private List<ConflictDialog.Clash> uploadClashes(List<IncomingFile> files, Set<String> existingFiles,
                                                     Set<String> existing) {
        final var conflicts = new ArrayList<ConflictDialog.Clash>();
        for (final var file : files) {
            if (stored(file) && existingFiles.contains(file.name())) {
                conflicts.add(new ConflictDialog.Clash(file.name(), file.type(), false));
            }
            final var folderName = newFolderName(file);
            if (folderName != null && existing.contains(folderName)) {
                conflicts.add(new ConflictDialog.Clash(folderName, StorageEntry.FOLDER_TYPE, true, true));
            }
        }
        return conflicts;
    }

    /** Whether the upload itself ends up in the folder: always, unless it's an archive extracted without keeping it. */
    private static boolean stored(IncomingFile file) {
        return file.extract() == null || file.extract().keepArchive();
    }

    /** The folder an archive is extracted into, or null when it isn't extracted into a new folder. */
    private String newFolderName(IncomingFile file) {
        if (file.extract() == null || !file.extract().newFolder()) {
            return null;
        }
        return workspace.getCatalog().archiveFormat(file.name())
                .filter(m -> !m.format().singleFile())
                .map(ArchiveFormat.Match::baseName)
                .orElse(null);
    }

    private void resolveConflicts(Incoming incoming, List<String> destination, Set<String> existing,
                                  Map<String, ConflictChoice> choices) {
        final var taken = new HashSet<>(existing);
        final var planned = new ArrayList<Planned>();
        for (final var file : incoming.files()) {
            plan(file, choices, taken).ifPresent(planned::add);
        }
        final var folders = UploadQueue.PlannedFolder.plan(incoming.folders(), choices, taken);
        // never empty here, so nothing planned means everything was skipped
        if (planned.isEmpty() && folders.isEmpty()) {
            final var skipped = incoming.itemCount();
            final var noun = incoming.folders().isEmpty() ? "file" : "item";
            Notices.show(Formats.plural(skipped, noun) + " skipped", "info");
            return;
        }
        enqueue(destination, planned, folders);
    }



    /** How the upload itself is stored after the user's choice. */
    private record Storing(boolean store, String finalName, boolean replace) {
    }

    /** One upload as planned after the user's choices; empty when nothing is left to do. */
    private Optional<Planned> plan(IncomingFile file, Map<String, ConflictChoice> choices, Set<String> taken) {
        final var storing = storing(file, choices, taken);
        final var match = file.extract() == null ? null : workspace.getCatalog().archiveFormat(file.name()).orElse(null);
        if (match == null) {
            return storing.store()
                    ? Optional.of(new Planned(file, storing.finalName(), storing.replace())) : Optional.empty();
        }
        var folderName = newFolderName(file);
        if (folderName != null) {
            final var choice = choices.get(folderName);
            if (choice == ConflictChoice.SKIP) {
                return Optional.empty();
            }
            if (choice == ConflictChoice.KEEP_BOTH) {
                folderName = Workspace.keepBothName(folderName, taken);
            }
            taken.add(folderName);
        }
        final var extraction = new UploadQueue.Extraction(match.format(), match.baseName(), folderName,
                storing.store(), file.extract().clash(), extractLimits);
        return Optional.of(new Planned(file, storing.finalName(), storing.replace(), extraction));
    }

    private static Storing storing(IncomingFile file, Map<String, ConflictChoice> choices, Set<String> taken) {
        if (!stored(file)) {
            return new Storing(false, file.name(), false);
        }
        final var choice = choices.get(file.name());
        var finalName = file.name();
        if (choice == ConflictChoice.KEEP_BOTH) {
            finalName = Workspace.keepBothName(file.name(), taken);
        }
        taken.add(finalName);
        // a skipped archive name still lets its contents be extracted, just without keeping it
        return new Storing(choice != ConflictChoice.SKIP, finalName, choice == ConflictChoice.REPLACE);
    }

    private void enqueue(List<String> destination, List<Planned> planned, List<UploadQueue.PlannedFolder> folders) {
        try {
            final var tasks = workspace.upload(root, destination, planned, maxUploadBytes);
            folders.forEach(f -> workspace.uploadFolder(root, destination, f));
            if (layout != null) {
                layout.upload(tasks);
            }
        } catch (StorageException e) {
            Notices.error(e.getMessage());
            return;
        }
        refresh();
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        pollRegistration = attachEvent.getUI().addPollListener(e -> autoRefreshTick());
        layout = MainLayout.of(this).orElse(null);
        if (layout != null) {
            taskRegistration = layout.addTaskListener(foldersChanged -> {
                if (foldersChanged) {
                    afterChange();
                } else {
                    refresh();
                }
            });
        }
        updatePollInterval();
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (pollRegistration != null) {
            pollRegistration.remove();
            pollRegistration = null;
        }
        if (taskRegistration != null) {
            taskRegistration.remove();
            taskRegistration = null;
        }
        if (layout != null) {
            layout.setViewPollInterval(-1);
            layout = null;
        }
        super.onDetach(detachEvent);
    }

    /** Polls at the auto refresh interval, or not at all; the layout polls faster while tasks run. */
    private void updatePollInterval() {
        if (layout != null) {
            layout.setViewPollInterval(autoRefresh.on() ? (int) autoRefresh.getInterval().toMillis() : -1);
        }
    }

    private void tasksChanged() {
        if (layout != null) {
            layout.tasksChanged();
        }
    }

    // ---- downloads ------------------------------------------------------------------------

    private void download(List<Located> targets) {
        final var entries = targets.stream().map(Located::entry).toList();
        final var single = entries.size() == 1 ? entries.getFirst() : null;
        final var zipBase = single != null ? single.name() : path.isEmpty() ? root.name() : path.getLast();
        final var provider = workspace.provider(root);
        final var handler = Downloads.of(provider, entries, zipBase + ".zip");
        // the link stays in place: removing it right after the click can unregister the download before the
        // browser's request for it arrives; the next download replaces its target instead
        downloadLink.setHref(handler);
        downloadLink.getElement().executeJs("this.click()");
    }

    private static List<String> append(List<String> base, String name) {
        final var out = new ArrayList<>(base);
        out.add(name);
        return List.copyOf(out);
    }
}
