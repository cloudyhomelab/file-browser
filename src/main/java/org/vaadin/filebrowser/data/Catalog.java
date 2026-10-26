package org.vaadin.filebrowser.data;

import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Static reference data, loaded from {@code data/*.json}. */
@Component
public class Catalog implements Serializable {

    private static final String GENERIC_TYPE = "generic";

    record MockData(LocalDate referenceDate, List<IncomingFile> sampleFiles) implements Serializable {
    }

    private final Map<RootType, RootTypeInfo> rootTypes;
    private final Map<String, FileTypeInfo> fileTypes;
    private final MockData mock;

    public Catalog() {
        final var mapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();
        this.rootTypes = read(mapper, "data/root-types.json", new TypeReference<Map<RootType, RootTypeInfo>>() { });
        this.fileTypes = read(mapper, "data/file-types.json", new TypeReference<LinkedHashMap<String, FileTypeInfo>>() { });
        this.mock = read(mapper, "data/mock-data.json", new TypeReference<MockData>() { });
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

    public LocalDate referenceDate() {
        return mock.referenceDate();
    }

    public List<IncomingFile> sampleFiles() {
        return mock.sampleFiles();
    }
}
