CREATE TABLE file_system (
    id          TEXT NOT NULL PRIMARY KEY,
    name        TEXT NOT NULL,
    type        TEXT NOT NULL,
    endpoint    TEXT NOT NULL UNIQUE,
    region      TEXT NOT NULL,
    access      TEXT NOT NULL,
    created_by  TEXT NOT NULL,
    created_at  TEXT NOT NULL
);
