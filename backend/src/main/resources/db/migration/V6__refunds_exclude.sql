ALTER TABLE txn ADD COLUMN excluded BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE txn ADD COLUMN refund_of_txn_id BIGINT REFERENCES txn (id) ON DELETE SET NULL;
CREATE INDEX idx_txn_refund_of ON txn (refund_of_txn_id) WHERE refund_of_txn_id IS NOT NULL;
ALTER TABLE category ADD COLUMN exclude_from_reports BOOLEAN NOT NULL DEFAULT FALSE;
