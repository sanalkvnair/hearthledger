package com.hearthledger.common

/**
 * SQL predicate for report queries: true unless the transaction is excluded itself or sits in a
 * category (or subcategory of a category) flagged "exclude from reports". [alias] is the txn table alias.
 * Balance-type queries (card bills, savings) intentionally do not use it.
 */
fun reportIncluded(alias: String = "t") =
    """(NOT $alias.excluded AND NOT EXISTS (
         SELECT 1 FROM category xc LEFT JOIN category xp ON xp.id = xc.parent_id
         WHERE xc.id = $alias.category_id AND (xc.exclude_from_reports OR COALESCE(xp.exclude_from_reports, FALSE))))"""

/** Signed contribution of a transaction to expenses: refunds reduce them. */
fun expenseDelta(alias: String = "t") = "CASE WHEN $alias.type = 'EXPENSE' THEN $alias.base_amount WHEN $alias.type = 'REFUND' THEN -$alias.base_amount ELSE 0 END"
