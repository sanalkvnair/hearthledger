CREATE TABLE app_user (
    id          BIGSERIAL PRIMARY KEY,
    email       TEXT NOT NULL UNIQUE,
    name        TEXT NOT NULL,
    avatar_url  TEXT,
    google_sub  TEXT NOT NULL UNIQUE,
    locale      TEXT NOT NULL DEFAULT 'en',
    theme       TEXT NOT NULL DEFAULT 'auto',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE refresh_token (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash  TEXT NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked     BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE family (
    id                      BIGSERIAL PRIMARY KEY,
    name                    TEXT NOT NULL,
    base_currency           CHAR(3) NOT NULL,
    cycle_start_day         INT NOT NULL DEFAULT 1 CHECK (cycle_start_day BETWEEN 1 AND 31),
    buffer_months           INT NOT NULL DEFAULT 3,
    buffer_lookback_months  INT NOT NULL DEFAULT 3,
    llm_import_enabled      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE family_member (
    id         BIGSERIAL PRIMARY KEY,
    family_id  BIGINT NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    user_id    BIGINT NOT NULL UNIQUE REFERENCES app_user (id) ON DELETE CASCADE,
    role       TEXT NOT NULL,
    joined_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE invitation (
    id           BIGSERIAL PRIMARY KEY,
    family_id    BIGINT NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    code         TEXT NOT NULL UNIQUE,
    email        TEXT,
    role         TEXT NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    created_by   BIGINT NOT NULL REFERENCES app_user (id),
    accepted_by  BIGINT REFERENCES app_user (id)
);

CREATE TABLE exchange_rate (
    id              BIGSERIAL PRIMARY KEY,
    family_id       BIGINT NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    from_currency   CHAR(3) NOT NULL,
    to_currency     CHAR(3) NOT NULL,
    rate            NUMERIC(19, 8) NOT NULL CHECK (rate > 0),
    effective_date  DATE NOT NULL,
    UNIQUE (family_id, from_currency, to_currency, effective_date)
);

CREATE TABLE category (
    id                 BIGSERIAL PRIMARY KEY,
    family_id          BIGINT NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    parent_id          BIGINT REFERENCES category (id) ON DELETE CASCADE,
    type               TEXT NOT NULL,
    name               TEXT NOT NULL,
    icon               TEXT,
    color              TEXT,
    default_necessity  TEXT,
    archived           BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_category_family ON category (family_id);

CREATE TABLE account (
    id               BIGSERIAL PRIMARY KEY,
    family_id        BIGINT NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    owner_user_id    BIGINT REFERENCES app_user (id),
    name             TEXT NOT NULL,
    type             TEXT NOT NULL,
    currency         CHAR(3) NOT NULL,
    opening_balance  NUMERIC(19, 4) NOT NULL DEFAULT 0,
    institution      TEXT,
    archived         BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_account_family ON account (family_id);

CREATE TABLE txn (
    id             BIGSERIAL PRIMARY KEY,
    family_id      BIGINT NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    user_id        BIGINT NOT NULL REFERENCES app_user (id),
    type           TEXT NOT NULL,
    amount         NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    currency       CHAR(3) NOT NULL,
    fx_rate        NUMERIC(19, 8) NOT NULL,
    base_amount    NUMERIC(19, 4) NOT NULL,
    date           DATE NOT NULL,
    account_id     BIGINT REFERENCES account (id),
    to_account_id  BIGINT REFERENCES account (id),
    category_id    BIGINT REFERENCES category (id),
    commitment     TEXT,
    necessity      TEXT,
    note           TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_txn_family_date ON txn (family_id, date);

CREATE TABLE period_adjustment (
    id                     BIGSERIAL PRIMARY KEY,
    family_id              BIGINT NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    period_start           DATE NOT NULL,
    actual_opening_balance NUMERIC(19, 4) NOT NULL,
    note                   TEXT,
    UNIQUE (family_id, period_start)
);
