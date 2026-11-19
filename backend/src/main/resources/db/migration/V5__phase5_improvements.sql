-- 1. business-day aware periods
ALTER TABLE family ADD COLUMN adjust_period_to_business_day BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE family ADD COLUMN holiday_country CHAR(2) NOT NULL DEFAULT 'SE';

-- 4/6. account ownership and credit card cycle
ALTER TABLE account ADD COLUMN ownership TEXT NOT NULL DEFAULT 'FAMILY';
ALTER TABLE account ADD COLUMN statement_day SMALLINT CHECK (statement_day BETWEEN 1 AND 31);
ALTER TABLE account ADD COLUMN due_day SMALLINT CHECK (due_day BETWEEN 1 AND 31);

CREATE TABLE account_owner (
    account_id BIGINT NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    user_id    BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    PRIMARY KEY (account_id, user_id)
);
CREATE INDEX idx_account_owner_user ON account_owner (user_id);

INSERT INTO account_owner (account_id, user_id) SELECT id, owner_user_id FROM account WHERE owner_user_id IS NOT NULL;
UPDATE account SET ownership = 'PRIVATE' WHERE owner_user_id IS NOT NULL;
ALTER TABLE account DROP COLUMN owner_user_id;

-- 3. per-user default accounts
ALTER TABLE app_user ADD COLUMN default_expense_account_id BIGINT REFERENCES account (id) ON DELETE SET NULL;
ALTER TABLE app_user ADD COLUMN default_income_account_id BIGINT REFERENCES account (id) ON DELETE SET NULL;
ALTER TABLE app_user ADD COLUMN default_savings_source_account_id BIGINT REFERENCES account (id) ON DELETE SET NULL;

-- 7. valuation history for savings / investment accounts
CREATE TABLE account_valuation (
    id           BIGSERIAL PRIMARY KEY,
    account_id   BIGINT NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    valued_on    DATE NOT NULL,
    market_value NUMERIC(19, 4) NOT NULL,
    note         TEXT,
    UNIQUE (account_id, valued_on)
);

INSERT INTO account_valuation (account_id, valued_on, market_value)
SELECT account_id, COALESCE(valued_at, CURRENT_DATE), current_value FROM savings_detail WHERE current_value IS NOT NULL;
ALTER TABLE savings_detail DROP COLUMN current_value;
ALTER TABLE savings_detail DROP COLUMN valued_at;

-- 8. loan types
ALTER TABLE loan ADD COLUMN loan_type TEXT NOT NULL DEFAULT 'ANNUITY';
ALTER TABLE loan ADD COLUMN amortization_rate NUMERIC(9, 4);
ALTER TABLE loan ADD COLUMN balloon_amount NUMERIC(19, 4) NOT NULL DEFAULT 0;
ALTER TABLE loan ADD COLUMN down_payment NUMERIC(19, 4) NOT NULL DEFAULT 0;
ALTER TABLE loan ADD COLUMN setup_fee NUMERIC(19, 4) NOT NULL DEFAULT 0;
ALTER TABLE loan ADD COLUMN monthly_fee NUMERIC(19, 4) NOT NULL DEFAULT 0;
ALTER TABLE loan ADD COLUMN opening_balance NUMERIC(19, 4);
-- last schedule number already booked, so deleted payments are not generated again
ALTER TABLE loan ADD COLUMN generated_through INT NOT NULL DEFAULT 0;
ALTER TABLE loan ADD COLUMN created_by BIGINT REFERENCES app_user (id);
ALTER TABLE loan ALTER COLUMN tenure_months DROP NOT NULL;

CREATE TABLE loan_rate (
    id             BIGSERIAL PRIMARY KEY,
    loan_id        BIGINT NOT NULL REFERENCES loan (id) ON DELETE CASCADE,
    effective_from DATE NOT NULL,
    annual_rate    NUMERIC(9, 4) NOT NULL CHECK (annual_rate >= 0),
    UNIQUE (loan_id, effective_from)
);

ALTER TABLE txn ADD COLUMN loan_interest_part NUMERIC(19, 4);
ALTER TABLE txn ADD COLUMN loan_principal_part NUMERIC(19, 4);
-- loan payments are generated from the loan schedule; this keeps generation idempotent
ALTER TABLE txn ADD COLUMN loan_schedule_no INT;
CREATE UNIQUE INDEX uq_txn_loan_schedule ON txn (loan_id, loan_schedule_no) WHERE loan_schedule_no IS NOT NULL;

-- existing loans: their recurring-rule payments become schedule payments, the rule is dropped
UPDATE loan l SET created_by = r.user_id FROM recurring_rule r WHERE r.id = l.recurring_rule_id;
UPDATE txn t SET loan_schedule_no = n.rn
FROM (SELECT id, row_number() OVER (PARTITION BY loan_id ORDER BY date, id) AS rn FROM txn WHERE loan_id IS NOT NULL AND recurring_rule_id IS NOT NULL) n
WHERE t.id = n.id;
UPDATE loan l SET generated_through = COALESCE((SELECT max(loan_schedule_no) FROM txn WHERE loan_id = l.id), 0);
UPDATE loan SET recurring_rule_id = NULL;
DELETE FROM recurring_rule WHERE loan_id IS NOT NULL;
ALTER TABLE loan DROP COLUMN recurring_rule_id;
