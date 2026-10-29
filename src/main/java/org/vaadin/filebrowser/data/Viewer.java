package org.vaadin.filebrowser.data;

/** How the page shows a file of a given media type. */
public enum Viewer {
    IMAGE, VIDEO, AUDIO, PDF, TEXT;

    private static final String PDF_TYPE = "application/pdf";

    public static Viewer of(String mediaType) {
        if (mediaType.startsWith("image/")) {
            return IMAGE;
        }
        if (mediaType.startsWith("video/")) {
            return VIDEO;
        }
        if (mediaType.startsWith("audio/")) {
            return AUDIO;
        }
        return PDF_TYPE.equals(mediaType) ? PDF : TEXT;
    }
}
