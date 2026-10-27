package org.vaadin.filebrowser.ui.dialog;

import java.util.Map;
import java.util.function.Function;

import org.vaadin.filebrowser.storage.DiskStorageType;
import org.vaadin.filebrowser.storage.S3StorageType;

/** The form for each storage type the add dialog offers, by the type's key. */
final class RootForms {

    private static final Map<String, Function<RootForm.Context, RootForm>> FORMS = Map.of(
            DiskStorageType.KEY, DiskForm::new,
            S3StorageType.KEY, S3Form::new);

    private RootForms() {
    }

    static boolean has(String type) {
        return FORMS.containsKey(type);
    }

    static RootForm create(String type, RootForm.Context context) {
        return FORMS.get(type).apply(context);
    }
}
