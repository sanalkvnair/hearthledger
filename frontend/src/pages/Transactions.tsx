import { ActionIcon, Badge, Button, Group, Loader, Modal, Pagination, Select, Stack, Table, Text, Title } from '@mantine/core';
import { IconChevronLeft, IconChevronRight, IconPencil, IconPlus, IconTrash } from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api';
import { TxnForm } from '../components/TxnForm';
import { addDays, money, shortDate } from '../format';
import { canWrite, useApiMutation, useMe } from '../hooks';
import type { Period, Txn, TxnPage, TxnType } from '../types';

const TYPE_COLOR: Record<TxnType, string> = { INCOME: 'teal', EXPENSE: 'red', TRANSFER: 'blue', REFUND: 'lime' };

export function TransactionsPage() {
  const { t, i18n } = useTranslation();
  const { data: me } = useMe();
  const family = me!.family!;
  const [date, setDate] = useState<string | undefined>();
  const [type, setType] = useState<string | null>(null);
  const [excludedFilter, setExcludedFilter] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [editing, setEditing] = useState<Txn | 'new' | null>(null);

  const { data: period } = useQuery({
    queryKey: ['period', date],
    queryFn: () => api<Period>(`/periods/current${date ? `?date=${date}` : ''}`),
  });
  const { data } = useQuery({
    queryKey: ['transactions', period?.start, type, excludedFilter, page],
    enabled: !!period,
    queryFn: () => {
      const q = new URLSearchParams({ from: period!.start, to: period!.end, page: String(page) });
      if (type) q.set('type', type);
      if (excludedFilter) q.set('excluded', excludedFilter);
      return api<TxnPage>(`/transactions?${q}`);
    },
  });
  const remove = useApiMutation((id: number) => api(`/transactions/${id}`, 'DELETE'), ['transactions', 'dashboard']);
  const writer = canWrite(me?.role);

  if (!period) return <Loader />;
  const move = (d: string) => { setDate(d); setPage(0); };

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={2}>{t('nav.transactions')}</Title>
        {writer && <Button leftSection={<IconPlus size={16} />} onClick={() => setEditing('new')}>{t('txn.new')}</Button>}
      </Group>
      <Group>
        <ActionIcon variant="default" aria-label={t('common.previous')} onClick={() => move(addDays(period.start, -1))}><IconChevronLeft size={16} /></ActionIcon>
        <Text fw={500}>{shortDate(period.start, i18n.language)} – {shortDate(period.end, i18n.language)}</Text>
        <ActionIcon variant="default" aria-label={t('common.next')} onClick={() => move(addDays(period.end, 1))}><IconChevronRight size={16} /></ActionIcon>
        <Select
          placeholder={t('common.type')}
          clearable
          data={[{ value: 'INCOME', label: t('txn.income') }, { value: 'REFUND', label: t('txn.refund') }, { value: 'EXPENSE', label: t('txn.expense') }, { value: 'TRANSFER', label: t('txn.transfer') }]}
          value={type}
          onChange={(v) => { setType(v); setPage(0); }}
          w={160}
        />
        <Select
          placeholder={t('txn.reports')}
          clearable
          data={[{ value: 'false', label: t('txn.included') }, { value: 'true', label: t('txn.excludedLabel') }]}
          value={excludedFilter}
          onChange={(v) => { setExcludedFilter(v); setPage(0); }}
          w={180}
        />
      </Group>

      <Table.ScrollContainer minWidth={800}>
        <Table striped highlightOnHover>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('common.date')}</Table.Th>
              <Table.Th>{t('common.type')}</Table.Th>
              <Table.Th>{t('txn.category')} / {t('txn.account')}</Table.Th>
              <Table.Th>{t('txn.member')}</Table.Th>
              <Table.Th>{t('common.note')}</Table.Th>
              <Table.Th ta="right">{t('common.amount')}</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {data?.items.map((x) => (
              <Table.Tr key={x.id} style={x.effectivelyExcluded ? { opacity: 0.6 } : undefined}>
                <Table.Td>{shortDate(x.date, i18n.language)}</Table.Td>
                <Table.Td>
                  <Badge color={TYPE_COLOR[x.type]} variant="light">{t(`txn.${x.type.toLowerCase()}`)}</Badge>
                  {x.commitment && x.commitment !== 'ONE_TIME' && <Badge ml={4} variant="outline" color="gray">{t(`commitment.${x.commitment}`)}</Badge>}
                  {x.necessity && <Badge ml={4} variant="dot" color="gray">{t(`necessity.${x.necessity}`)}</Badge>}
                  {x.effectivelyExcluded && <Badge ml={4} variant="outline" color="orange">{t('txn.excludedLabel')}</Badge>}
                </Table.Td>
                <Table.Td>
                  {x.type === 'TRANSFER' ? `${x.accountName} → ${x.toAccountName}` : `${x.categoryName ?? '—'}${x.accountName ? ` · ${x.accountName}` : ''}`}
                  {x.type === 'EXPENSE' && x.refundedAmount != null && <Text size="xs" c="lime.8">{t('txn.refundedOf', { refunded: money(x.refundedAmount, family.baseCurrency, i18n.language), total: money(x.baseAmount, family.baseCurrency, i18n.language) })}</Text>}
                </Table.Td>
                <Table.Td>{x.userName}</Table.Td>
                <Table.Td>{x.note}</Table.Td>
                <Table.Td ta="right">
                  <Text c={x.type === 'INCOME' || x.type === 'REFUND' ? 'teal' : x.type === 'EXPENSE' ? 'red' : undefined} fw={500}>
                    {x.type === 'REFUND' ? '+' : ''}{money(x.amount, x.currency, i18n.language)}
                  </Text>
                  {x.currency !== family.baseCurrency && <Text size="xs" c="dimmed">{money(x.baseAmount, family.baseCurrency, i18n.language)}</Text>}
                </Table.Td>
                <Table.Td>
                  {writer && (
                    <Group gap={4} wrap="nowrap">
                      <ActionIcon variant="subtle" aria-label={t('common.edit')} onClick={() => setEditing(x)}><IconPencil size={16} /></ActionIcon>
                      <ActionIcon variant="subtle" color="red" aria-label={t('common.delete')} onClick={() => confirm(t('common.delete') + '?') && remove.mutate(x.id)}><IconTrash size={16} /></ActionIcon>
                    </Group>
                  )}
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Table.ScrollContainer>
      {data?.items.length === 0 && <Text c="dimmed">{t('txn.empty')}</Text>}
      {data && data.total > data.size && <Pagination total={Math.ceil(data.total / data.size)} value={page + 1} onChange={(p) => setPage(p - 1)} />}

      <Modal opened={editing !== null} onClose={() => setEditing(null)} title={t('txn.new')} size="lg">
        {editing && <TxnForm family={family} txn={editing === 'new' ? undefined : editing} onDone={() => setEditing(null)} />}
      </Modal>
    </Stack>
  );
}
