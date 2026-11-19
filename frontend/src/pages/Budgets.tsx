import { Button, Card, Group, NumberInput, Progress, Select, Stack, Text, Title } from '@mantine/core';
import { IconTrash } from '@tabler/icons-react';
import { ActionIcon } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api';
import { money } from '../format';
import { isAdmin, useApiMutation, useCategories, useMe } from '../hooks';
import type { BudgetProgress } from '../types';

export const STATUS_COLOR = { OK: 'teal', WARNING: 'yellow', EXCEEDED: 'red' } as const;

export function BudgetsPage() {
  const { t, i18n } = useTranslation();
  const { data: me } = useMe();
  const { data: categories = [] } = useCategories();
  const { data: budgets = [] } = useQuery({ queryKey: ['budgets'], queryFn: () => api<BudgetProgress[]>('/budgets') });
  const [categoryId, setCategoryId] = useState<string | null>(null);
  const [amount, setAmount] = useState<number | string>('');
  const save = useApiMutation(() => api('/budgets', 'PUT', { categoryId: Number(categoryId), amount: Number(amount) }), ['budgets'], () => { setAmount(''); setCategoryId(null); });
  const remove = useApiMutation((id: number) => api(`/budgets/${id}`, 'DELETE'), ['budgets']);
  const base = me!.family!.baseCurrency;
  const fmt = (n: number) => money(n, base, i18n.language);

  return (
    <Stack>
      <Title order={2}>{t('budgets.title')}</Title>
      <Text c="dimmed" size="sm">{t('budgets.hint')}</Text>
      {isAdmin(me?.role) && (
        <Group align="flex-end">
          <Select label={t('txn.category')} searchable data={categories.filter((c) => c.type === 'EXPENSE').map((c) => ({ value: String(c.id), label: c.name }))} value={categoryId} onChange={setCategoryId} />
          <NumberInput label={`${t('budgets.limit')} (${base})`} value={amount} onChange={setAmount} min={0} decimalScale={2} />
          <Button disabled={!categoryId || !amount} loading={save.isPending} onClick={() => save.mutate(undefined)}>{t('budgets.set')}</Button>
        </Group>
      )}
      {budgets.map((b) => (
        <Card key={b.id} withBorder padding="md">
          <Group justify="space-between" mb={4}>
            <Text fw={600}>{b.categoryName}</Text>
            <Group gap="xs">
              <Text size="sm">{fmt(b.spent)} / {fmt(b.amount)}</Text>
              {isAdmin(me?.role) && <ActionIcon size="sm" variant="subtle" color="red" aria-label={t('common.delete')} onClick={() => remove.mutate(b.id)}><IconTrash size={14} /></ActionIcon>}
            </Group>
          </Group>
          <Progress value={Math.min(b.percent, 100)} color={STATUS_COLOR[b.status]} />
          <Text size="xs" c={b.remaining < 0 ? 'red' : 'dimmed'} mt={4}>{t('budgets.left')}: {fmt(b.remaining)} ({b.percent}%)</Text>
        </Card>
      ))}
    </Stack>
  );
}
