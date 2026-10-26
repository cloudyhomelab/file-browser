CREATE TABLE starred_item (
    email    TEXT NOT NULL,
    root_id  TEXT NOT NULL REFERENCES file_system (id) ON DELETE CASCADE,
    path     TEXT NOT NULL,
    PRIMARY KEY (email, root_id, path)
);

CREATE TABLE user_preference (
    email           TEXT NOT NULL PRIMARY KEY,
    layout          TEXT NOT NULL,
    density         TEXT NOT NULL,
    sort_key        TEXT NOT NULL,
    sort_ascending  INTEGER NOT NULL
);
