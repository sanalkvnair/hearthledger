CREATE TABLE loan (
    id                BIGSERIAL PRIMARY KEY,
    family_id         BIGINT NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    owner_user_id     BIGINT REFERENCES app_user (id),
    name              TEXT NOT NULL,
    lender            TEXT,
    currency          CHAR(3) NOT NULL,
    principal         NUMERIC(19, 4) NOT NULL CHECK (principal > 0),
    interest_rate     NUMERIC(9, 4) NOT NULL DEFAULT 0,
    tenure_months     INT NOT NULL CHECK (tenure_months > 0),
    emi               NUMERIC(19, 4) NOT NULL CHECK (emi > 0),
    start_date        DATE NOT NULL,
    term              TEXT NOT NULL,
    category_id       BIGINT REFERENCES category (id) ON DELETE SET NULL,
    account_id        BIGINT REFERENCES account (id) ON DELETE SET NULL,
    recurring_rule_id BIGINT,
    active            BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE recurring_rule (
    id           BIGSERIAL PRIMARY KEY,
    family_id    BIGINT NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    user_id      BIGINT NOT NULL REFERENCES app_user (id),
    type         TEXT NOT NULL,
    amount       NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    currency     CHAR(3) NOT NULL,
    account_id   BIGINT REFERENCES account (id) ON DELETE SET NULL,
    category_id  BIGINT REFERENCES category (id) ON DELETE SET NULL,
    commitment   TEXT,
    necessity    TEXT,
    note         TEXT,
    frequency    TEXT NOT NULL,
    start_date   DATE NOT NULL,
    end_date     DATE,
    next_index   INT NOT NULL DEFAULT 0,
    loan_id      BIGINT REFERENCES loan (id) ON DELETE CASCADE,
    active       BOOLEAN NOT NULL DEFAULT TRUE
);

ALTER TABLE loan ADD FOREIGN KEY (recurring_rule_id) REFERENCES recurring_rule (id) ON DELETE SET NULL;

ALTER TABLE txn ADD COLUMN loan_id BIGINT REFERENCES loan (id) ON DELETE SET NULL;
ALTER TABLE txn ADD COLUMN recurring_rule_id BIGINT REFERENCES recurring_rule (id) ON DELETE SET NULL;
-- makes automatic generation idempotent
CREATE UNIQUE INDEX uq_txn_rule_date ON txn (recurring_rule_id, date) WHERE recurring_rule_id IS NOT NULL;

CREATE TABLE savings_detail (
    account_id       BIGINT PRIMARY KEY REFERENCES account (id) ON DELETE CASCADE,
    interest_rate    NUMERIC(9, 4),
    start_date       DATE,
    maturity_date    DATE,
    maturity_amount  NUMERIC(19, 4),
    current_value    NUMERIC(19, 4),
    valued_at        DATE
);

CREATE TABLE budget (
    id           BIGSERIAL PRIMARY KEY,
    family_id    BIGINT NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    category_id  BIGINT NOT NULL REFERENCES category (id) ON DELETE CASCADE,
    amount       NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    UNIQUE (family_id, category_id)
);
