package org.vaadin.filebrowser.ui.dialog;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.service.Workspace;
import org.vaadin.filebrowser.service.Workspace.ConflictChoice;
import org.vaadin.filebrowser.ui.Buttons;
import org.vaadin.filebrowser.ui.FileGlyph;
import org.vaadin.filebrowser.ui.Segmented;
import org.vaadin.filebrowser.ui.Ui;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.function.SerializableConsumer;

/**
 * Per-item decision for uploads, moves or copies whose names already exist in the destination folder. Clashes
 * that involve a folder offer only Keep both or Skip: a folder is never replaced, nor replaces anything.
 */
public class ConflictDialog extends FbDialog {

    private static final List<Segmented.Option<ConflictChoice>> OPTIONS = List.of(
            Segmented.Option.text(ConflictChoice.REPLACE, "Replace"),
            Segmented.Option.text(ConflictChoice.KEEP_BOTH, "Keep both"),
            Segmented.Option.text(ConflictChoice.SKIP, "Skip"));
    private static final List<Segmented.Option<ConflictChoice>> FOLDER_OPTIONS = List.of(
            Segmented.Option.text(ConflictChoice.KEEP_BOTH, "Keep both"),
            Segmented.Option.text(ConflictChoice.SKIP, "Skip"));

    private final Purpose purpose;
    private final Catalog catalog;
    private final List<Clash> clashes;
    private final Set<String> existingNames;
    private final Set<String> folderNames;
    private final Map<String, ConflictChoice> choices = new LinkedHashMap<>();
    private final Div applyAll = Ui.div("fb-conflict-all");
    private final Div rows = Ui.div("fb-conflict-list");
    private final Span summary = Ui.span("fb-dialog-summary", "");

    public enum Purpose { UPLOAD, MOVE, COPY }

    /**
     * A name that already exists in the destination.
     *
     * @param type   the incoming item's file type, for its glyph
     * @param folder whether a folder is involved on either side; for an upload, the new folder an
     *               archive would be extracted into
     */
    public record Clash(String name, String type, boolean folder) implements Serializable {
    }

    public ConflictDialog(Purpose purpose, Catalog catalog, List<Clash> clashes, int totalCount, Set<String> existingNames,
                          SerializableConsumer<Map<String, ConflictChoice>> onResolve) {
        super(title(purpose, clashes.size()), "warn", Tone.DANGER, Width.WIDE);
        this.purpose = purpose;
        this.catalog = catalog;
        this.clashes = clashes;
        this.existingNames = existingNames;
        this.folderNames = clashes.stream().filter(Clash::folder).map(Clash::name).collect(Collectors.toSet());
        clashes.forEach(c -> choices.put(c.name(), ConflictChoice.KEEP_BOTH));

        final var introText = switch (purpose) {
            case UPLOAD -> clashes.size() + " of " + totalCount + " uploaded file" + (totalCount > 1 ? "s" : "")
                    + " match names already in this folder. Choose what to do with each.";
            case MOVE, COPY -> clashes.size() + " of " + totalCount + " item" + (totalCount > 1 ? "s" : "")
                    + " being " + (purpose == Purpose.MOVE ? "moved" : "copied")
                    + " match names already in the destination. Choose what to do with each.";
        };
        final var intro = new Paragraph(introText);
        intro.setClassName("fb-dialog-text fb-dialog-text--s");
        add(intro);
        if (clashes.size() > 1) {
            add(applyAll);
        }
        add(rows);

        final var proceedLabel = switch (purpose) {
            case UPLOAD -> "Continue upload";
            case MOVE -> "Move";
            case COPY -> "Copy";
        };
        final var proceed = Buttons.create(Buttons.Kind.PRIMARY, proceedLabel, null, () -> {
            close();
            final var resolved = Map.copyOf(choices);
            onResolve.accept(resolved);
        });
        final var cancel = cancelButton();
        footer(summary, cancel, proceed);
        render();
    }

    private static String title(Purpose purpose, int count) {
        final var noun = purpose == Purpose.UPLOAD ? "file" : "item";
        final var where = purpose == Purpose.UPLOAD ? "here" : "in the destination";
        return count + " " + noun + (count > 1 ? "s" : "") + " already exist" + (count > 1 ? "" : "s") + " " + where;
    }

    private void render() {
        final var distinct = choices.values().stream().distinct().toList();
        applyAll.removeAll();
        final var applyAllLabel = Ui.span("fb-conflict-all-label", "Apply to all");
        final var applyAllChoice = new Segmented<>("fb-seg--danger-first", OPTIONS,
                distinct.size() == 1 ? distinct.getFirst() : null, v -> {
                    choices.replaceAll((k, old) -> folderNames.contains(k) && v == ConflictChoice.REPLACE
                            ? ConflictChoice.KEEP_BOTH : v);
                    render();
                });
        applyAll.add(applyAllLabel, applyAllChoice);

        rows.removeAll();
        for (final var c : clashes) {
            final var choice = choices.get(c.name());
            final var detail = Ui.div("fb-conflict-detail fb-conflict-detail--" + choice.name().toLowerCase(Locale.ROOT).replace('_', '-'));
            detail(detail, c, choice);
            final var glyph = new FileGlyph(c.type(), catalog);
            final var nameText = Ui.span("", c.name());
            final var name = Ui.div("fb-conflict-name", nameText);
            final var names = Ui.div("fb-conflict-names", name, detail);
            final var picker = new Segmented<>(c.folder() ? "fb-seg--folder" : "fb-seg--danger-first",
                    c.folder() ? FOLDER_OPTIONS : OPTIONS, choice, v -> {
                choices.put(c.name(), v);
                render();
            });
            final var row = Ui.div("fb-conflict-row" + (choice == ConflictChoice.SKIP ? " fb-conflict-row--skip" : ""),
                    glyph, names, picker);
            rows.add(row);
        }

        final var counts = new EnumMap<ConflictChoice, Integer>(ConflictChoice.class);
        choices.values().forEach(v -> counts.merge(v, 1, Integer::sum));
        final var parts = new ArrayList<String>();
        if (counts.containsKey(ConflictChoice.REPLACE)) {
            parts.add(counts.get(ConflictChoice.REPLACE) + " replaced");
        }
        if (counts.containsKey(ConflictChoice.KEEP_BOTH)) {
            parts.add(counts.get(ConflictChoice.KEEP_BOTH) + " kept");
        }
        if (counts.containsKey(ConflictChoice.SKIP)) {
            parts.add(counts.get(ConflictChoice.SKIP) + " skipped");
        }
        final var summaryText = String.join(" · ", parts);
        summary.setText(summaryText);
    }

    private void detail(Div detail, Clash clash, ConflictChoice choice) {
        final var extraction = purpose == Purpose.UPLOAD && clash.folder();
        switch (choice) {
            case REPLACE -> detail.add("Overwrites the existing file");
            case KEEP_BOTH -> {
                final var lead = new Span(extraction ? "Extracts into " : switch (purpose) {
                    case UPLOAD -> "Uploads as ";
                    case MOVE -> "Moves as ";
                    case COPY -> "Copies as ";
                });
                final var newName = Workspace.keepBothName(clash.name(), existingNames);
                final var newNameText = Ui.span("fb-mono", newName);
                detail.add(lead, newNameText);
            }
            case SKIP -> detail.add(extraction ? "Won't be extracted" : switch (purpose) {
                case UPLOAD -> "Won't be uploaded";
                case MOVE -> "Won't be moved";
                case COPY -> "Won't be copied";
            });
        }
    }
}
