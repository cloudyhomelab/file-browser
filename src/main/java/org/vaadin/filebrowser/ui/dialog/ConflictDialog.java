package org.vaadin.filebrowser.ui.dialog;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.IncomingFile;
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

/** Per-file decision for uploads whose names already exist in the destination folder. */
public class ConflictDialog extends FbDialog {

    private static final List<Segmented.Option<ConflictChoice>> OPTIONS = List.of(
            Segmented.Option.text(ConflictChoice.REPLACE, "Replace"),
            Segmented.Option.text(ConflictChoice.KEEP_BOTH, "Keep both"),
            Segmented.Option.text(ConflictChoice.SKIP, "Skip"));

    private final Catalog catalog;
    private final List<IncomingFile> conflicts;
    private final Set<String> existingNames;
    private final Map<String, ConflictChoice> choices = new LinkedHashMap<>();
    private final Div applyAll = Ui.div("fb-conflict-all");
    private final Div rows = Ui.div("fb-conflict-list");
    private final Span summary = Ui.span("fb-dialog-summary", "");

    public ConflictDialog(Catalog catalog, List<IncomingFile> conflicts, int totalCount, Set<String> existingNames,
                          SerializableConsumer<Map<String, ConflictChoice>> onResolve) {
        super(title(conflicts.size()), "warn", Tone.DANGER, Width.WIDE);
        this.catalog = catalog;
        this.conflicts = conflicts;
        this.existingNames = existingNames;
        conflicts.forEach(c -> choices.put(c.name(), ConflictChoice.KEEP_BOTH));

        final var intro = new Paragraph(conflicts.size() + " of " + totalCount + " uploaded file" + (totalCount > 1 ? "s" : "")
                + " match names already in this folder. Choose what to do with each.");
        intro.setClassName("fb-dialog-text fb-dialog-text--s");
        add(intro);
        if (conflicts.size() > 1) {
            add(applyAll);
        }
        add(rows);

        final var proceed = Buttons.create(Buttons.Kind.PRIMARY, "Continue upload", null, () -> {
            close();
            final var resolved = Map.copyOf(choices);
            onResolve.accept(resolved);
        });
        final var cancel = cancelButton();
        footer(summary, cancel, proceed);
        render();
    }

    private static String title(int n) {
        return n + " file" + (n > 1 ? "s" : "") + " already exist" + (n > 1 ? "" : "s") + " here";
    }

    private void render() {
        final var distinct = choices.values().stream().distinct().toList();
        applyAll.removeAll();
        final var applyAllLabel = Ui.span("fb-conflict-all-label", "Apply to all");
        final var applyAllChoice = new Segmented<>("fb-seg--danger-first", OPTIONS,
                distinct.size() == 1 ? distinct.getFirst() : null, v -> {
                    choices.replaceAll((k, old) -> v);
                    render();
                });
        applyAll.add(applyAllLabel, applyAllChoice);

        rows.removeAll();
        for (final var c : conflicts) {
            final var choice = choices.get(c.name());
            final var detail = Ui.div("fb-conflict-detail fb-conflict-detail--" + choice.name().toLowerCase().replace('_', '-'));
            switch (choice) {
                case REPLACE -> detail.add("Overwrites the existing file");
                case KEEP_BOTH -> {
                    final var lead = new Span("Uploads as ");
                    final var newName = Workspace.keepBothName(c.name(), existingNames);
                    final var newNameText = Ui.span("fb-mono", newName);
                    detail.add(lead, newNameText);
                }
                case SKIP -> detail.add("Won't be uploaded");
            }
            final var glyph = new FileGlyph(c.type(), catalog);
            final var nameText = Ui.span("", c.name());
            final var name = Ui.div("fb-conflict-name", nameText);
            final var names = Ui.div("fb-conflict-names", name, detail);
            final var picker = new Segmented<>("fb-seg--danger-first", OPTIONS, choice, v -> {
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
}
