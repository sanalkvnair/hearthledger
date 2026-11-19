import { Button, Group, NumberInput, SegmentedControl, Select, Stack, TextInput } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api';
import { isoDate } from '../format';
import { useAccounts, useApiMutation, useCategories, useMe } from '../hooks';
import { COMMITMENTS, NECESSITIES, SAVINGS_TYPES, type Commitment, FREQUENCIES, type Family, type Frequency, type Loan, type Necessity, type Txn } from '../types';

type Kind = 'INCOME' | 'EXPENSE' | 'SAVINGS' | 'TRANSFER';

const kindOf = (t?: Txn): Kind => {
  if (!t) return 'EXPENSE';
  return t.type;
};

export function TxnForm({ family, txn, onDone }: { family: Family; txn?: Txn; onDone: () => void }) {
  const { t } = useTranslation();
  const { data: categories = [] } = useCategories();
  const { data: accounts = [] } = useAccounts();
  const { data: me } = useMe();
  const defaultFor = (k: Kind) => {
    const id = k === 'EXPENSE' ? me?.defaultExpenseAccountId : k === 'INCOME' ? me?.defaultIncomeAccountId : k === 'SAVINGS' ? me?.defaultSavingsSourceAccountId : null;
    return id != null ? String(id) : null;
  };
  const [kind, setKind] = useState<Kind>(kindOf(txn));
  const [amount, setAmount] = useState<number | string>(txn?.amount ?? '');
  const [currency, setCurrency] = useState(txn?.currency ?? family.baseCurrency);
  const [fxRate, setFxRate] = useState<number | string>('');
  const [date, setDate] = useState(txn?.date ?? isoDate(new Date()));
  const [accountId, setAccountId] = useState<string | null>(txn ? (txn.accountId?.toString() ?? null) : defaultFor('EXPENSE'));
  const [toAccountId, setToAccountId] = useState<string | null>(txn?.toAccountId?.toString() ?? null);
  const [categoryId, setCategoryId] = useState<string | null>(txn?.categoryId?.toString() ?? null);
  const [commitment, setCommitment] = useState<Commitment>(txn?.commitment ?? 'ONE_TIME');
  const [necessity, setNecessity] = useState<Necessity | null>(txn?.necessity ?? null);
  const [note, setNote] = useState(txn?.note ?? '');
  const [loanId, setLoanId] = useState<string | null>(txn?.loanId?.toString() ?? null);
  const [repeat, setRepeat] = useState<string | null>(null);
  const [endDate, setEndDate] = useState('');
  const { data: loans = [] } = useQuery({ queryKey: ['loans'], queryFn: () => api<Loan[]>('/loans') });
  const isLoan = commitment === 'SHORT_TERM_LOAN' || commitment === 'LONG_TERM_LOAN';
  const canRepeat = !txn && (kind === 'EXPENSE' || kind === 'INCOME');

  const catType = kind === 'INCOME' ? 'INCOME' : 'EXPENSE';
  const categoryOptions = useMemo(() => {
    const active = categories.filter((c) => c.type === catType && !c.archived);
    return active
      .filter((c) => c.parentId === null)
      .map((p) => ({
        group: p.name,
        items: [{ value: String(p.id), label: p.name }, ...active.filter((c) => c.parentId === p.id).map((c) => ({ value: String(c.id), label: `${p.name} › ${c.name}` }))],
      }));
  }, [categories, catType]);

  const accountOptions = accounts.filter((a) => !a.archived).map((a) => ({ value: String(a.id), label: `${a.name} (${a.currency})` }));
  const savingsOptions = accounts.filter((a) => !a.archived && SAVINGS_TYPES.includes(a.type)).map((a) => ({ value: String(a.id), label: `${a.name} (${a.currency})` }));
  const hasDestination = kind === 'SAVINGS' || kind === 'TRANSFER';

  const submit = () => {
      const body = {
        type: kind === 'SAVINGS' ? 'TRANSFER' : kind,
        amount: Number(amount),
        currency,
        fxRate: fxRate === '' ? null : Number(fxRate),
        date,
        accountId: accountId ? Number(accountId) : null,
        toAccountId: hasDestination && toAccountId ? Number(toAccountId) : null,
        categoryId: !hasDestination && categoryId ? Number(categoryId) : null,
        commitment: kind === 'EXPENSE' ? commitment : null,
        necessity: kind === 'EXPENSE' ? necessity : null,
        note: note || null,
        loanId: kind === 'EXPENSE' && isLoan && loanId ? Number(loanId) : null,
      };
      if (canRepeat && repeat) {
        return api('/recurring-rules', 'POST', {
          type: kind, amount: body.amount, currency, frequency: repeat, startDate: date, endDate: endDate || null,
          accountId: body.accountId, categoryId: body.categoryId,
          commitment: kind === 'EXPENSE' ? (commitment === 'ONE_TIME' ? 'RECURRING' : commitment) : null,
          necessity: body.necessity, note: body.note,
        });
      }
      return txn ? api(`/transactions/${txn.id}`, 'PUT', body) : api('/transactions', 'POST', body);
  };
  const keys = ['transactions', 'dashboard', 'recurring', 'buffer', 'loans', 'card-bills', 'savings'];
  const save = useApiMutation(submit, keys, onDone);
  // keeps the form open with date, account and category so several entries can be made in a row
  const saveAnother = useApiMutation(submit, keys, () => {
    setAmount('');
    setNote('');
    setFxRate('');
  });

  const valid = Number(amount) > 0 && currency.length === 3 && (!hasDestination || (accountId && toAccountId));

  return (
    <Stack>
      <SegmentedControl
        fullWidth
        value={kind}
        onChange={(v) => { setKind(v as Kind); setCategoryId(null); setToAccountId(null); if (!txn) setAccountId(defaultFor(v as Kind)); }}
        data={[
          { value: 'EXPENSE', label: t('txn.expense') },
          { value: 'INCOME', label: t('txn.income') },
          { value: 'SAVINGS', label: t('txn.savingsKind') },
          { value: 'TRANSFER', label: t('txn.transfer') },
        ]}
      />
      <Group grow align="flex-start">
        <NumberInput label={t('common.amount')} value={amount} onChange={setAmount} min={0} decimalScale={2} data-autofocus />
        <TextInput label={t('common.currency')} maxLength={3} value={currency} onChange={(e) => setCurrency(e.currentTarget.value.toUpperCase())} />
        <TextInput label={t('common.date')} type="date" value={date} onChange={(e) => setDate(e.currentTarget.value)} />
      </Group>
      {currency !== family.baseCurrency && (
        <NumberInput label={`${t('txn.fxRate')} ${currency}→${family.baseCurrency}`} value={fxRate} onChange={setFxRate} min={0} decimalScale={6} />
      )}
      <Select label={hasDestination ? t('txn.from') : t('txn.account')} data={accountOptions} value={accountId} onChange={setAccountId} clearable searchable />
      {hasDestination && (
        <Select
          label={kind === 'SAVINGS' ? t('txn.toSavings') : t('txn.to')}
          data={kind === 'SAVINGS' ? savingsOptions : accountOptions.filter((a) => a.value !== accountId)}
          value={toAccountId}
          onChange={setToAccountId}
          searchable
        />
      )}
      {!hasDestination && (
        <Select
          label={t('txn.category')}
          data={categoryOptions}
          value={categoryId}
          onChange={(v) => {
            setCategoryId(v);
            const def = categories.find((c) => String(c.id) === v)?.defaultNecessity;
            if (kind === 'EXPENSE' && def) setNecessity(def);
          }}
          clearable
          searchable
        />
      )}
      {kind === 'EXPENSE' && (
        <Group grow>
          <Select label={t('txn.commitment')} data={COMMITMENTS.map((c) => ({ value: c, label: t(`commitment.${c}`) }))} value={commitment} onChange={(v) => setCommitment(v as Commitment)} allowDeselect={false} />
          <Select label={t('txn.necessity')} data={NECESSITIES.map((n) => ({ value: n, label: t(`necessity.${n}`) }))} value={necessity} onChange={(v) => setNecessity(v as Necessity | null)} clearable />
        </Group>
      )}
      {kind === 'EXPENSE' && isLoan && (
        <Select label={t('loans.linkLoan')} clearable data={loans.filter((l) => l.active).map((l) => ({ value: String(l.id), label: l.name }))} value={loanId} onChange={setLoanId} />
      )}
      {canRepeat && (
        <Group grow align="flex-end">
          <Select label={t('recurring.repeat')} clearable placeholder={t('recurring.never')} data={FREQUENCIES.map((f: Frequency) => ({ value: f, label: t(`frequency.${f}`) }))} value={repeat} onChange={setRepeat} />
          {repeat && <TextInput type="date" label={t('recurring.endDate')} value={endDate} onChange={(e) => setEndDate(e.currentTarget.value)} />}
        </Group>
      )}
      <TextInput label={t('common.note')} value={note} onChange={(e) => setNote(e.currentTarget.value)} />
      <Group justify="flex-end">
        <Button variant="default" onClick={onDone}>{t('common.cancel')}</Button>
        {!txn && !repeat && (
          <Button variant="light" loading={saveAnother.isPending} disabled={!valid} onClick={() => saveAnother.mutate(undefined)}>{t('p5.saveAnother')}</Button>
        )}
        <Button loading={save.isPending} disabled={!valid} onClick={() => save.mutate(undefined)}>{t('common.save')}</Button>
      </Group>
    </Stack>
  );
}
