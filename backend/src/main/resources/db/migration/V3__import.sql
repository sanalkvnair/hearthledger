ALTER TABLE txn ADD COLUMN import_hash TEXT;
CREATE INDEX idx_txn_import_hash ON txn (family_id, import_hash) WHERE import_hash IS NOT NULL;

CREATE TABLE categorization_rule (
    id          BIGSERIAL PRIMARY KEY,
    family_id   BIGINT NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    keyword     TEXT NOT NULL,
    category_id BIGINT NOT NULL REFERENCES category (id) ON DELETE CASCADE,
    priority    INT NOT NULL DEFAULT 0
);

CREATE TABLE import_template (
    id         BIGSERIAL PRIMARY KEY,
    family_id  BIGINT NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    mapping    TEXT NOT NULL,
    UNIQUE (family_id, name)
);
