package org.vaadin.filebrowser.ui;

import java.util.List;

import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.Viewer;
import org.vaadin.filebrowser.storage.StorageEntry;
import org.vaadin.filebrowser.web.FileUrls;

import com.vaadin.componentfactory.pdfviewer.PdfViewer;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.ModalityMode;
import com.vaadin.flow.component.Shortcuts;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.AnchorTarget;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.IFrame;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.function.SerializableConsumer;

/**
 * Shows the files of a folder one at a time, each in the viewer its media type calls for, loading it from its
 * {@code /files} URL; a file it can't show gets the reason and a download. Replacing a viewer drops its element,
 * which ends the browser's request for the file.
 */
public class Lightbox extends Dialog {

    private final Catalog catalog;
    private final String rootId;
    private final List<StorageEntry> files;
    private final SerializableConsumer<StorageEntry> onShown;
    private final SerializableConsumer<StorageEntry> onDownload;
    private final Span name = Ui.span("fb-lightbox-name", "");
    private final Span position = Ui.span("fb-lightbox-position", "");
    private final Anchor newTab = new Anchor();
    private final NativeButton previous;
    private final NativeButton next;
    private final Div stage = Ui.div("fb-lightbox-stage");
    private int index;

    /** The files among {@code entries}, in the order given; folders don't open here. */
    public static List<StorageEntry> files(List<StorageEntry> entries) {
        return entries.stream().filter(e -> !e.folder()).toList();
    }

    /**
     * @param files   the files that can be stepped through, as {@link #files} gives them
     * @param onShown called with each file as it is shown, the first included
     */
    public Lightbox(Catalog catalog, String rootId, List<StorageEntry> files, StorageEntry start,
                    SerializableConsumer<StorageEntry> onShown, SerializableConsumer<StorageEntry> onDownload) {
        this.catalog = catalog;
        this.rootId = rootId;
        this.files = List.copyOf(files);
        this.onShown = onShown;
        this.onDownload = onDownload;
        addClassName("fb-lightbox");
        setModality(ModalityMode.STRICT);
        setDraggable(false);
        setResizable(false);
        setCloseOnOutsideClick(true);

        final var title = Ui.div("fb-lightbox-title", name, position);
        final var download = Buttons.create(Buttons.Kind.SECONDARY, "Download", "download",
                () -> onDownload.accept(current()));
        final var newTabIcon = new Icon("link");
        final var newTabLabel = new Span("Open in new tab");
        newTab.add(newTabIcon, newTabLabel);
        newTab.addClassName("fb-ghost-btn");
        newTab.setTarget(AnchorTarget.BLANK);
        newTab.getElement().setAttribute("rel", "noopener noreferrer");
        final var close = Ui.iconButton("cross", "Close", this::close);
        final var actions = Ui.div("fb-lightbox-actions", download, newTab, close);
        final var head = Ui.div("fb-lightbox-head", title, actions);

        previous = Ui.iconButton("angle-left", "Previous", this::previous);
        previous.addClassName("fb-lightbox-step");
        next = Ui.iconButton("angle-right", "Next", this::next);
        next.addClassName("fb-lightbox-step");
        final var body = Ui.div("fb-lightbox-body", previous, stage, next);
        add(head, body);

        Shortcuts.addShortcutListener(this, this::previous, Key.ARROW_LEFT).listenOn(this);
        Shortcuts.addShortcutListener(this, this::next, Key.ARROW_RIGHT).listenOn(this);
        index = Math.max(0, this.files.indexOf(start));
        show();
    }

    private StorageEntry current() {
        return files.get(index);
    }

    void previous() {
        if (index > 0) {
            index--;
            show();
        }
    }

    void next() {
        if (index < files.size() - 1) {
            index++;
            show();
        }
    }

    private void show() {
        final var file = current();
        final var url = FileUrls.of(rootId, file.path());
        final var viewer = catalog.viewType(file.name()).map(Viewer::of);
        final var reachable = FileUrls.reachable(file.path());
        name.setText(file.name());
        position.setText((index + 1) + " of " + files.size());
        newTab.setHref(url);
        newTab.setVisible(reachable && viewer.isPresent());
        previous.setEnabled(index > 0);
        next.setEnabled(index < files.size() - 1);
        stage.removeAll();
        if (viewer.isEmpty()) {
            showUnavailable(file, "Files of this type can't be previewed.");
        } else if (!reachable) {
            showUnavailable(file, "Names with %, ; or \\ in them, or in a folder above them, can't be previewed.");
        } else {
            final var view = view(viewer.orElseThrow(), file, url);
            stage.add(view);
        }
        onShown.accept(file);
    }

    private Component view(Viewer viewer, StorageEntry file, String url) {
        return switch (viewer) {
            case IMAGE -> {
                final var image = new Image(url, file.name());
                image.addClassName("fb-lightbox-image");
                failOnError(image.getElement(), file);
                yield image;
            }
            case VIDEO -> media("video", file, url);
            case AUDIO -> media("audio", file, url);
            case PDF -> {
                final var pdf = new PdfViewer();
                pdf.setSrc(url);
                pdf.setCustomTitle(file.name());
                pdf.setAddDownloadButton(false);
                pdf.addClassName("fb-lightbox-pdf");
                yield pdf;
            }
            case TEXT -> {
                final var frame = new IFrame(url);
                frame.setTitle(file.name());
                frame.addClassName("fb-lightbox-frame");
                // the response is sandboxed already; this keeps the frame inert should that ever change
                frame.getElement().setAttribute("sandbox", "");
                yield frame;
            }
        };
    }

    private Component media(String tag, StorageEntry file, String url) {
        final var element = new Element(tag);
        element.setAttribute("src", url);
        element.setAttribute("controls", true);
        element.setAttribute("preload", "metadata");
        failOnError(element, file);
        final var holder = Ui.div("fb-lightbox-media fb-lightbox-media--" + tag);
        holder.getElement().appendChild(element);
        return holder;
    }

    private void failOnError(Element element, StorageEntry file) {
        element.addEventListener("error", e -> {
            if (file.equals(current())) {
                showUnavailable(file, "It couldn't be loaded.");
            }
        });
    }

    private void showUnavailable(StorageEntry file, String reason) {
        final var icon = new Icon("warn");
        final var text = Ui.span("fb-lightbox-failed-text", "“" + file.name() + "” can't be shown here.");
        final var reasonText = Ui.span("fb-lightbox-failed-reason", reason);
        final var download = Buttons.create(Buttons.Kind.PRIMARY, "Download", "download",
                () -> onDownload.accept(file));
        final var failed = Ui.div("fb-lightbox-failed", icon, text, reasonText, download);
        stage.removeAll();
        stage.add(failed);
    }
}
