package com.openfamilybudget.recurring

import com.openfamilybudget.common.money
import com.openfamilybudget.currency.CurrencyService
import com.openfamilybudget.family.FamilyRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
class RecurringService(
    private val rules: RecurringRuleRepository,
    private val families: FamilyRepository,
    private val currency: CurrencyService,
    private val jdbc: JdbcClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    @Scheduled(cron = "0 15 0 * * *")
    fun generateAllDue() {
        rules.findByActiveTrue().forEach { runCatching { generateDue(it) }.onFailure { e -> log.warn("Recurring rule {} failed: {}", it.id, e.message) } }
    }

    /** Creates every occurrence up to [today]. Idempotent thanks to the unique (rule, date) index. */
    @Transactional
    fun generateDue(rule: RecurringRule, today: LocalDate = LocalDate.now()): Int {
        val baseCurrency = families.findById(rule.familyId).orElseThrow().baseCurrency
        var index = rule.nextIndex
        var created = 0
        while (true) {
            val date = rule.frequency.occurrence(rule.startDate, index)
            if (date.isAfter(today) || (rule.endDate != null && date.isAfter(rule.endDate))) break
            val rate = try {
                currency.rateToBase(rule.familyId, baseCurrency, rule.currency, date)
            } catch (e: Exception) {
                log.warn("Rule {} paused at {}: {}", rule.id, date, e.message)
                break // retried by the next run once a rate exists
            }
            created += jdbc.sql(
                """INSERT INTO txn (family_id, user_id, type, amount, currency, fx_rate, base_amount, date, account_id, category_id,
                                    commitment, necessity, note, loan_id, recurring_rule_id)
                   VALUES (:f, :u, :type, :amount, :cur, :rate, :base, :date, :acc, :cat, :com, :nec, :note, :loan, :rule)
                   ON CONFLICT DO NOTHING""",
            ).param("f", rule.familyId).param("u", rule.userId).param("type", rule.type.name).param("amount", rule.amount)
                .param("cur", rule.currency).param("rate", rate).param("base", (rule.amount * rate).money()).param("date", date)
                .param("acc", rule.accountId).param("cat", rule.categoryId).param("com", rule.commitment?.name)
                .param("nec", rule.necessity?.name).param("note", rule.note).param("loan", rule.loanId).param("rule", rule.id)
                .update()
            index++
        }
        if (index != rule.nextIndex) rules.save(rule.copy(nextIndex = index))
        return created
    }
}
