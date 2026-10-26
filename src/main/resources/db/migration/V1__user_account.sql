CREATE TABLE user_account (
    email           TEXT NOT NULL PRIMARY KEY,
    name            TEXT NOT NULL,
    last_provider   TEXT NOT NULL,
    first_sign_in   TEXT NOT NULL,
    last_sign_in    TEXT NOT NULL
);
