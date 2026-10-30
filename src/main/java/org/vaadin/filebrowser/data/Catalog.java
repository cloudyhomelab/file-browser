package org.vaadin.filebrowser.data;

import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Static reference data, loaded from {@code data/*.json}. */
@Component
public class Catalog implements Serializable {

    private static final String GENERIC_TYPE = "generic";
    private static final String DEFAULT_SIGN_IN_ERROR = "default";
    private static final String LOCAL_KIND = "disk";

    private final Map<String, RootTypeInfo> rootTypes;
    private final Map<String, FileTypeInfo> fileTypes;
    private final Map<String, String> signInErrors;
    private final MountKinds mountKinds;
    /** By name suffix, e.g. {@code .tar.gz}. */
    private final List<Map.Entry<String, ArchiveFormat>> archiveFormats;
    /** Media type by lower-case extension, for files that may be shown in the page. */
    private final Map<String, String> viewTypes;
    /** What a new file system starts with. */
    @Getter
    private final RootOptions rootOptions;
    @Getter
    private final RootOptionLimits rootOptionLimits;

    /** @param tints the colours kinds may use; each has a {@code fb-tint-*} class in styles.css */
    record RootOptionsFile(@JsonProperty("defaults") RootOptions defaults,
                           @JsonProperty("limits") RootOptionLimits limits) implements Serializable {
    }

    record MountKinds(List<String> tints, Map<String, String> storeTypes, Map<String, KindInfo> kinds)
            implements Serializable {
    }

    public Catalog() {
        final var mapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();
        this.rootTypes = read(mapper, "data/root-types.json", new TypeReference<>() { });
        this.fileTypes = read(mapper, "data/file-types.json", new TypeReference<>() { });
        this.signInErrors = read(mapper, "data/sign-in-errors.json", new TypeReference<>() { });
        this.mountKinds = read(mapper, "data/mount-kinds.json", new TypeReference<>() { });
        mountKinds.storeTypes().values().stream()
                .filter(kind -> !mountKinds.kinds().containsKey(kind))
                .findFirst()
                .ifPresent(kind -> {
                    throw new IllegalStateException("data/mount-kinds.json maps a store type to unknown kind " + kind);
                });
        mountKinds.kinds().forEach((key, kind) -> {
            if (kind.label() == null || kind.icon() == null || !mountKinds.tints().contains(kind.tint())) {
                throw new IllegalStateException("data/mount-kinds.json needs a label, an icon and one of the tints "
                        + mountKinds.tints() + " for kind " + key);
            }
        });
        final var formatsBySuffix = read(mapper, "data/archive-formats.json",
                new TypeReference<Map<String, ArchiveFormat>>() { });
        this.archiveFormats = List.copyOf(formatsBySuffix.entrySet());
        final var extensionsByViewType = read(mapper, "data/view-types.json",
                new TypeReference<Map<String, List<String>>>() { });
        this.viewTypes = byExtension(extensionsByViewType);
        final var strict = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .build();
        final RootOptionsFile optionsFile = read(strict, "data/root-options.json", new TypeReference<>() { });
        final var defaultsProblem = optionsFile.limits().problem(optionsFile.defaults());
        if (defaultsProblem != null) {
            throw new IllegalStateException("data/root-options.json defaults: " + defaultsProblem);
        }
        this.rootOptions = optionsFile.defaults();
        this.rootOptionLimits = optionsFile.limits();
    }

    private static Map<String, String> byExtension(Map<String, List<String>> extensionsByType) {
        final var out = new HashMap<String, String>();
        extensionsByType.forEach((type, extensions) -> extensions.forEach(ext -> {
            if (out.put(ext, type) != null) {
                throw new IllegalStateException("data/view-types.json lists extension " + ext + " more than once");
            }
        }));
        return Map.copyOf(out);
    }

    private static <T> T read(JsonMapper mapper, String path, TypeReference<T> type) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return mapper.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
    }

    /** @return null for a type that isn't described */
    public RootTypeInfo rootType(String type) {
        return rootTypes.get(type);
    }

    /** The described file-system types, in the order they are offered. */
    public List<String> rootTypeKeys() {
        return List.copyOf(rootTypes.keySet());
    }

    public FileTypeInfo fileType(String type) {
        return fileTypes.getOrDefault(type, fileTypes.get(GENERIC_TYPE));
    }

    /** The archive format a name's suffix says, longest suffix first ({@code .tar.gz} before {@code .gz}). */
    public Optional<ArchiveFormat.Match> archiveFormat(String fileName) {
        final var lower = fileName.toLowerCase(Locale.ROOT);
        return archiveFormats.stream()
                .filter(e -> lower.endsWith(e.getKey()) && lower.length() > e.getKey().length())
                .max(Map.Entry.comparingByKey(Comparator.comparingInt(String::length)))
                .map(e -> new ArchiveFormat.Match(e.getValue(), fileName.substring(0, fileName.length() - e.getKey().length())));
    }

    public String typeForFileName(String fileName) {
        final var dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return GENERIC_TYPE;
        }
        final var ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return fileTypes.entrySet().stream()
                .filter(e -> e.getValue().extensions().contains(ext))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(GENERIC_TYPE);
    }

    /** The media type a file is shown with in the page; empty when it may only be downloaded. */
    public Optional<String> viewType(String fileName) {
        final var dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        final var ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return Optional.ofNullable(viewTypes.get(ext));
    }

    /** The kind a file-store type is shown as; network and special ones are listed, anything else is a local disk. */
    public String mountKind(String fileStoreType) {
        return mountKinds.storeTypes().getOrDefault(fileStoreType, LOCAL_KIND);
    }

    public KindInfo kind(FsRoot root) {
        return mountKinds.kinds().get(root.kindKey());
    }

    public KindInfo kind(String kindKey) {
        return mountKinds.kinds().get(kindKey);
    }

    /** User-facing text for a sign-in failure code; unknown codes get the generic message. */
    public String signInError(String code) {
        return signInErrors.getOrDefault(code, signInErrors.get(DEFAULT_SIGN_IN_ERROR));
    }

}
