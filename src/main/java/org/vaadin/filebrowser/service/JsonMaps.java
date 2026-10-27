package org.vaadin.filebrowser.service;

import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Flat string maps kept as JSON text in the database, such as a file system's settings and secrets. */
final class JsonMaps {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, String>> MAP = new TypeReference<>() { };

    private JsonMaps() {
    }

    static String write(Map<String, String> map) {
        return JSON.writeValueAsString(map);
    }

    /** Entries without a value are left out, so a hand-edited {@code null} can't break reading. */
    static Map<String, String> read(String json) {
        final var map = new LinkedHashMap<String, String>();
        JSON.readValue(json, MAP).forEach((key, value) -> {
            if (value != null) {
                map.put(key, value);
            }
        });
        return map;
    }
}
