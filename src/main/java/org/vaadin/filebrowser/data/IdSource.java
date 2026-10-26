package org.vaadin.filebrowser.data;

import java.io.Serializable;

import java.util.concurrent.atomic.AtomicLong;

public final class IdSource implements Serializable {

    private final AtomicLong next = new AtomicLong(1);

    public String next() {
        return "n" + next.getAndIncrement();
    }
}
