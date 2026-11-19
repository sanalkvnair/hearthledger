CREATE TABLE audit_log (
    id        BIGSERIAL PRIMARY KEY,
    family_id BIGINT NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    user_id   BIGINT,
    method    TEXT NOT NULL,
    path      TEXT NOT NULL,
    entity    TEXT NOT NULL,
    entity_id BIGINT,
    status    INT NOT NULL,
    at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_family_at ON audit_log (family_id, at DESC);
