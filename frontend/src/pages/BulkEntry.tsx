import { ActionIcon, Alert, Button, Checkbox, Group, NumberInput, Select, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconPlus, IconTrash } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, ApiError } from '../api';
import { isoDate } from '../format';
import { canWrite, useAccounts, useCategories, useMe } from '../hooks';

interface Row {
  type: 'EXPENSE' | 'INCOME' | 'REFUND';
  excluded: boolean;
  date: string;
  amount: number | string;
  accountId: string | null;
  categoryId: string | null;
  note: string;
}

export function BulkEntryPage() {
  const { t } = useTranslation();
  const { data: me } = useMe();
  const { data: accounts = [] } = useAccounts();
  const { data: categories = [] } = useCategories();
  const qc = useQueryClient();
  const currency = me!.family!.baseCurrency;
  const defaultAccount = me?.defaultExpenseAccountId != null ? String(me.defaultExpenseAccountId) : null;
  const blank = (prev?: Row): Row => ({ type: prev?.type ?? 'EXPENSE', date: prev?.date ?? isoDate(new Date()), amount: '', accountId: prev?.accountId ?? defaultAccount, categoryId: null, note: '', excluded: false });
  const [rows, setRows] = useState<Row[]>([blank(), blank(), blank()]);
  const [errors, setErrors] = useState<Record<number, string>>({});
  const [busy, setBusy] = useState(false);

  const accountOptions = accounts.filter((a) => !a.archived).map((a) => ({ value: String(a.id), label: `${a.name} (${a.currency})` }));
  const categoryOptions = useMemo(
    () => (type: Row['type']) => categories.filter((c) => c.type === (type === 'INCOME' ? 'INCOME' : 'EXPENSE') && !c.archived).map((c) => ({ value: String(c.id), label: c.name })),
    [categories],
  );
  const accountCurrency = (id: string | null) => accounts.find((a) => String(a.id) === id)?.currency ?? currency;

  const update = (i: number, patch: Partial<Row>) => setRows((rs) => rs.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  const filled = rows.filter((r) => Number(r.amount) > 0);

  const save = async () => {
    setBusy(true);
    setErrors({});
    // keep track of the original row index so errors can be shown on the right line
    const indexes = rows.map((r, i) => (Number(r.amount) > 0 ? i : -1)).filter((i) => i >= 0);
    try {
      await api('/transactions/batch', 'POST', {
        items: indexes.map((i) => {
          const r = rows[i];
          return {
            type: r.type, amount: Number(r.amount), currency: accountCurrency(r.accountId), date: r.date,
            accountId: r.accountId ? Number(r.accountId) : null, categoryId: r.categoryId ? Number(r.categoryId) : null, note: r.note || null,
            commitment: r.type === 'EXPENSE' ? 'ONE_TIME' : null, excluded: r.excluded,
          };
        }),
      });
      notifications.show({ color: 'teal', message: t('p5.saved', { count: indexes.length }) });
      setRows([blank(rows[0]), blank(rows[0]), blank(rows[0])]);
      await Promise.all(['transactions', 'dashboard', 'buffer', 'savings'].map((k) => qc.invalidateQueries({ queryKey: [k] })));
    } catch (e) {
      if (e instanceof ApiError && e.errors) {
        setErrors(Object.fromEntries(e.errors.map((x) => [indexes[x.row], x.message])));
      } else {
        notifications.show({ color: 'red', message: (e as Error).message });
      }
    } finally {
      setBusy(false);
    }
  };

  if (!canWrite(me?.role)) return null;
  return (
    <Stack>
      <Title order={2}>{t('p5.bulk')}</Title>
      <Text c="dimmed" size="sm">{t('p5.bulkHint')}</Text>
      {Object.keys(errors).length > 0 && (
        <Alert color="red">
          {Object.entries(errors).map(([i, m]) => <div key={i}>{t('p5.rowError', { row: Number(i) + 1, message: m })}</div>)}
        </Alert>
      )}
      <Table.ScrollContainer minWidth={900}>
        <Table verticalSpacing="xs">
          <Table.Tbody>
            {rows.map((r, i) => (
              <Table.Tr key={i} bg={errors[i] ? 'var(--mantine-color-red-light)' : undefined}>
                <Table.Td w={120}>
                  <Select size="xs" allowDeselect={false} value={r.type} onChange={(v) => update(i, { type: v as Row['type'], categoryId: null })}
                    data={[{ value: 'EXPENSE', label: t('txn.expense') }, { value: 'INCOME', label: t('txn.income') }, { value: 'REFUND', label: t('txn.refund') }]} />
                </Table.Td>
                <Table.Td w={150}><TextInput size="xs" type="date" value={r.date} onChange={(e) => update(i, { date: e.currentTarget.value })} /></Table.Td>
                <Table.Td w={120}><NumberInput size="xs" min={0} decimalScale={2} value={r.amount} onChange={(v) => update(i, { amount: v })} /></Table.Td>
                <Table.Td><Select size="xs" searchable clearable data={accountOptions} value={r.accountId} onChange={(v) => update(i, { accountId: v })} /></Table.Td>
                <Table.Td><Select size="xs" searchable clearable data={categoryOptions(r.type)} value={r.categoryId} onChange={(v) => update(i, { categoryId: v })} /></Table.Td>
                <Table.Td><TextInput size="xs" placeholder={t('common.note')} value={r.note} onChange={(e) => update(i, { note: e.currentTarget.value })} /></Table.Td>
                <Table.Td w={60}><Checkbox size="xs" aria-label={t('txn.excludeLabel')} title={t('txn.excludeLabel')} checked={r.excluded} onChange={(e) => update(i, { excluded: e.currentTarget.checked })} /></Table.Td>
                <Table.Td w={40}>
                  <ActionIcon variant="subtle" color="red" aria-label={t('common.delete')} onClick={() => setRows((rs) => rs.filter((_, j) => j !== i))}><IconTrash size={14} /></ActionIcon>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Table.ScrollContainer>
      <Group>
        <Button variant="default" leftSection={<IconPlus size={16} />} onClick={() => setRows((rs) => [...rs, blank(rs[rs.length - 1])])}>{t('p5.addRow')}</Button>
        <Button loading={busy} disabled={filled.length === 0} onClick={save}>{t('p5.saveAll')} ({filled.length})</Button>
      </Group>
    </Stack>
  );
}
