import { Badge, Button, Group, Stack, Table, Text, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { api } from '../api';
import { money } from '../format';
import { canWrite, useApiMutation, useMe } from '../hooks';
import type { RecurringRule } from '../types';

export function RecurringPage() {
  const { t, i18n } = useTranslation();
  const { data: me } = useMe();
  const { data: rules = [] } = useQuery({ queryKey: ['recurring'], queryFn: () => api<RecurringRule[]>('/recurring-rules') });
  const stop = useApiMutation((id: number) => api(`/recurring-rules/${id}`, 'DELETE'), ['recurring', 'buffer']);
  return (
    <Stack>
      <Title order={2}>{t('recurring.title')}</Title>
      <Text c="dimmed" size="sm">{t('recurring.hint')}</Text>
      <Table striped>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{t('common.type')}</Table.Th>
            <Table.Th>{t('common.note')}</Table.Th>
            <Table.Th>{t('recurring.repeat')}</Table.Th>
            <Table.Th>{t('recurring.startDate')}</Table.Th>
            <Table.Th>{t('recurring.endDate')}</Table.Th>
            <Table.Th ta="right">{t('common.amount')}</Table.Th>
            <Table.Th />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {rules.map((r) => (
            <Table.Tr key={r.id}>
              <Table.Td><Badge variant="light" color={r.type === 'INCOME' ? 'teal' : 'red'}>{t(`txn.${r.type.toLowerCase()}`)}</Badge></Table.Td>
              <Table.Td>{r.note}</Table.Td>
              <Table.Td>{t(`frequency.${r.frequency}`)}</Table.Td>
              <Table.Td>{r.startDate}</Table.Td>
              <Table.Td>{r.endDate}</Table.Td>
              <Table.Td ta="right">{money(r.amount, r.currency, i18n.language)}</Table.Td>
              <Table.Td>{canWrite(me?.role) && <Button size="compact-xs" color="red" variant="subtle" onClick={() => confirm(t('recurring.stop') + '?') && stop.mutate(r.id)}>{t('recurring.stop')}</Button>}</Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
      <Group><Text size="sm" c="dimmed">{t('txn.new')} → {t('recurring.repeat')}</Text></Group>
    </Stack>
  );
}
