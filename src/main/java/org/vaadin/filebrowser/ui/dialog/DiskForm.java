package org.vaadin.filebrowser.ui.dialog;

import java.util.Map;

import org.vaadin.filebrowser.ui.Buttons;
import org.vaadin.filebrowser.ui.SubfolderLoader;

/** A folder on the server, checked as its path is typed and picked with "Browse…". */
public class DiskForm extends RootForm {

    public DiskForm(Context context) {
        super(context);
        if (!editing()) {
            final var workspace = context.workspace();
            final SubfolderLoader serverFolders = folder -> workspace.serverFolders("/" + String.join("/", folder));
            final var browse = Buttons.create(Buttons.Kind.SECONDARY, "Browse", "folder", () -> {
                final var picker = new FolderPickerDialog(serverFolders, workspace::endpointTaken, endpoint(),
                        endpointField::setValue);
                picker.open();
            });
            browse.addClassName("fb-field-browse");
            browse.setTooltipText("Browse server folders");
            endpointField.setSuffixComponent(browse);
        }
    }

    @Override
    public Map<String, String> settings() {
        return Map.of();
    }

    @Override
    protected String endpointProblem(String path) {
        final var found = context.workspace().probe(path);
        if (!found.exists()) {
            return "No folder exists at this path on the server.";
        }
        if (!found.directory()) {
            return "This path is a file, not a folder.";
        }
        if (!found.readable()) {
            return "The server can't read this folder.";
        }
        return null;
    }

    @Override
    public String readOnlyHint() {
        return "The server can't write to this folder, so it can only be added read-only.";
    }

    // when editing, only a folder that is there and refuses writes limits the choice
    @Override
    public boolean writable() {
        final var path = endpoint();
        if (getProblem() != null || path.isEmpty()) {
            return true;
        }
        final var found = context.workspace().probe(path);
        return found.writable() || editing() && !found.exists();
    }
}
