package org.vaadin.filebrowser.ui;

import java.util.List;

import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.IncomingFile;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.function.SerializableConsumer;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A container that accepts files dragged from the desktop (and optionally opens a file picker on
 * click). Only names and sizes are sent to the server: the file systems are simulated, so the
 * bytes are never needed. While files hover, the element carries the {@code fb-drag-over} class.
 */
public class FileDropArea extends Div {

    private record DroppedFile(String name, long size) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String INSTALL_SCRIPT = """
            const el = this, pickOnClick = $0;
            let depth = 0;
            const hasFiles = e => e.dataTransfer && [...(e.dataTransfer.types || [])].includes('Files');
            const send = files => el.$server.filesDropped(JSON.stringify([...files].map(f => ({ name: f.name, size: f.size }))));
            el.addEventListener('dragenter', e => { if (!hasFiles(e)) return; e.preventDefault(); depth++; el.classList.add('fb-drag-over'); });
            el.addEventListener('dragover', e => { if (hasFiles(e)) e.preventDefault(); });
            el.addEventListener('dragleave', e => { if (!hasFiles(e)) return; depth = Math.max(0, depth - 1); if (!depth) el.classList.remove('fb-drag-over'); });
            el.addEventListener('drop', e => { if (!hasFiles(e)) return; e.preventDefault(); depth = 0; el.classList.remove('fb-drag-over'); send(e.dataTransfer.files); });
            if (pickOnClick) {
              el.addEventListener('click', () => {
                const input = document.createElement('input');
                input.type = 'file'; input.multiple = true;
                input.addEventListener('change', () => { if (input.files.length) send(input.files); });
                input.click();
              });
            }
            """;

    private final Catalog catalog;
    private final boolean pickOnClick;
    private SerializableConsumer<List<IncomingFile>> onFiles = files -> { };

    public FileDropArea(Catalog catalog, boolean pickOnClick) {
        this.catalog = catalog;
        this.pickOnClick = pickOnClick;
    }

    public void onFiles(SerializableConsumer<List<IncomingFile>> handler) {
        this.onFiles = handler;
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        getElement().executeJs(INSTALL_SCRIPT, pickOnClick);
    }

    @ClientCallable
    private void filesDropped(String json) {
        final var listType = new TypeReference<List<DroppedFile>>() { };
        final var dropped = JSON.readValue(json, listType);
        final var files = dropped.stream()
                .map(f -> {
                    final var type = catalog.typeForFileName(f.name());
                    return new IncomingFile(f.name(), type, f.size());
                })
                .toList();
        if (!files.isEmpty()) {
            onFiles.accept(files);
        }
    }
}
