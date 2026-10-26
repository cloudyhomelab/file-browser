package org.vaadin.filebrowser.data;

import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.vaadin.filebrowser.storage.MountInfo;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Static reference data, loaded from {@code data/*.json}. */
@Component
public class Catalog implements Serializable {

    private static final String GENERIC_TYPE = "generic";
    private static final String DEFAULT_SIGN_IN_ERROR = "default";

    record MountKinds(Map<String, MountInfo.Kind> storeTypes, Map<String, KindInfo> kinds) implements Serializable {
    }

    record MockData(List<IncomingFile> sampleFiles) implements Serializable {
    }

    private final Map<RootType, RootTypeInfo> rootTypes;
    private final Map<String, FileTypeInfo> fileTypes;
    private final MockData mock;
    private final Map<String, String> signInErrors;
    private final MountKinds mountKinds;

    public Catalog() {
        final var mapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();
        this.rootTypes = read(mapper, "data/root-types.json", new TypeReference<Map<RootType, RootTypeInfo>>() { });
        this.fileTypes = read(mapper, "data/file-types.json", new TypeReference<LinkedHashMap<String, FileTypeInfo>>() { });
        this.mock = read(mapper, "data/mock-data.json", new TypeReference<MockData>() { });
        this.signInErrors = read(mapper, "data/sign-in-errors.json", new TypeReference<Map<String, String>>() { });
        this.mountKinds = read(mapper, "data/mount-kinds.json", new TypeReference<MountKinds>() { });
    }

    private static <T> T read(JsonMapper mapper, String path, TypeReference<T> type) {
        try (final var in = new ClassPathResource(path).getInputStream()) {
            return mapper.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
    }

    public RootTypeInfo rootType(RootType type) {
        return rootTypes.get(type);
    }

    public FileTypeInfo fileType(String type) {
        return fileTypes.getOrDefault(type, fileTypes.get(GENERIC_TYPE));
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

    /** Network and special file-store types are listed; anything else is a local disk. */
    public MountInfo.Kind mountKind(String fileStoreType) {
        return mountKinds.storeTypes().getOrDefault(fileStoreType, MountInfo.Kind.LOCAL);
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

    public List<IncomingFile> sampleFiles() {
        return mock.sampleFiles();
    }
}
