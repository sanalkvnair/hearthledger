import { Button, Card, Group, Modal, NumberInput, SimpleGrid, Stack, Table, Tabs, Text, TextInput, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api';
import { money } from '../format';
import { canWrite, useApiMutation, useMe } from '../hooks';
import type { SavingsAccount, SavingsOverview } from '../types';

function DetailsForm({ account, onDone }: { account: SavingsAccount; onDone: () => void }) {
  const { t } = useTranslation();
  const [rate, setRate] = useState<number | string>(account.interestRate ?? '');
  const [value, setValue] = useState<number | string>(account.manualValue ?? '');
  const [maturityDate, setMaturityDate] = useState(account.maturityDate ?? '');
  const [maturityAmount, setMaturityAmount] = useState<number | string>(account.maturityAmount ?? '');
  const num = (v: number | string) => (v === '' ? null : Number(v));
  const save = useApiMutation(
    () => api(`/savings/${account.accountId}/details`, 'PUT', {
      interestRate: num(rate), currentValue: num(value), maturityDate: maturityDate || null, maturityAmount: num(maturityAmount),
    }),
    ['savings', 'buffer'],
    onDone,
  );
  return (
    <Stack>
      <NumberInput label={t('savingsPage.manual')} value={value} onChange={setValue} decimalScale={2} />
      <NumberInput label={t('savingsPage.rate')} value={rate} onChange={setRate} decimalScale={2} />
      {account.type === 'FIXED_DEPOSIT' && (
        <Group grow>
          <TextInput type="date" label={t('savingsPage.maturity')} value={maturityDate} onChange={(e) => setMaturityDate(e.currentTarget.value)} />
          <NumberInput label={t('savingsPage.maturityAmount')} value={maturityAmount} onChange={setMaturityAmount} decimalScale={2} />
        </Group>
      )}
      <Group justify="flex-end">
        <Button variant="default" onClick={onDone}>{t('common.cancel')}</Button>
        <Button loading={save.isPending} onClick={() => save.mutate(undefined)}>{t('common.save')}</Button>
      </Group>
    </Stack>
  );
}

export function SavingsPage() {
  const { t, i18n } = useTranslation();
  const { data: me } = useMe();
  const { data } = useQuery({ queryKey: ['savings'], queryFn: () => api<SavingsOverview>('/savings') });
  const [editing, setEditing] = useState<SavingsAccount | null>(null);
  if (!data) return null;
  const fmt = (n: number) => money(n, data.baseCurrency, i18n.language);

  const table = (rows: SavingsAccount[]) => (
    <Table.ScrollContainer minWidth={800}>
      <Table striped>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{t('common.name')}</Table.Th>
            <Table.Th>{t('common.type')}</Table.Th>
            <Table.Th>{t('savingsPage.owner')}</Table.Th>
            <Table.Th ta="right">{t('savingsPage.contributed')}</Table.Th>
            <Table.Th ta="right">{t('savingsPage.value')}</Table.Th>
            <Table.Th ta="right">{t('savingsPage.gain')}</Table.Th>
            <Table.Th>{t('savingsPage.rate')}</Table.Th>
            <Table.Th>{t('savingsPage.maturity')}</Table.Th>
            <Table.Th />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {rows.map((a) => (
            <Table.Tr key={a.accountId}>
              <Table.Td>{a.name}{a.institution && <Text size="xs" c="dimmed">{a.institution}</Text>}</Table.Td>
              <Table.Td>{t(`accountType.${a.type}`)}</Table.Td>
              <Table.Td>{a.ownerName ?? t('savingsPage.shared')}</Table.Td>
              <Table.Td ta="right">{fmt(a.contributed)}</Table.Td>
              <Table.Td ta="right">{fmt(a.currentValue)}</Table.Td>
              <Table.Td ta="right"><Text c={a.gain < 0 ? 'red' : 'teal'} span>{fmt(a.gain)}</Text></Table.Td>
              <Table.Td>{a.interestRate != null ? `${a.interestRate}%` : ''}</Table.Td>
              <Table.Td>{a.maturityDate ?? ''}{a.maturityAmount != null && ` (${money(a.maturityAmount, a.currency, i18n.language)})`}</Table.Td>
              <Table.Td>{canWrite(me?.role) && <Button size="compact-xs" variant="subtle" onClick={() => setEditing(a)}>{t('savingsPage.details')}</Button>}</Table.Td>
            </Table.Tr>
          ))}
          <Table.Tr fw={700}>
            <Table.Td colSpan={3}>{t('savingsPage.total')}</Table.Td>
            <Table.Td ta="right">{fmt(rows.reduce((s, r) => s + r.contributed, 0))}</Table.Td>
            <Table.Td ta="right">{fmt(rows.reduce((s, r) => s + r.currentValue, 0))}</Table.Td>
            <Table.Td colSpan={4} />
          </Table.Tr>
        </Table.Tbody>
      </Table>
    </Table.ScrollContainer>
  );

  const members = data.byMember;
  return (
    <Stack>
      <Title order={2}>{t('savingsPage.title')}</Title>
      {data.accounts.length === 0 ? <Text c="dimmed">{t('savingsPage.empty')}</Text> : (
        <>
          <SimpleGrid cols={{ base: 1, sm: 3 }}>
            {members.map((m) => (
              <Card key={m.userId ?? 'shared'} withBorder padding="md">
                <Text size="sm" c="dimmed">{m.name ?? t('savingsPage.shared')}</Text>
                <Text size="xl" fw={700}>{fmt(m.currentValue)}</Text>
              </Card>
            ))}
          </SimpleGrid>
          <Tabs defaultValue="all">
            <Tabs.List mb="md">
              <Tabs.Tab value="all">{t('savingsPage.family')}</Tabs.Tab>
              {members.filter((m) => m.userId !== null).map((m) => <Tabs.Tab key={m.userId} value={String(m.userId)}>{m.name}</Tabs.Tab>)}
            </Tabs.List>
            <Tabs.Panel value="all">{table(data.accounts)}</Tabs.Panel>
            {members.filter((m) => m.userId !== null).map((m) => (
              <Tabs.Panel key={m.userId} value={String(m.userId)}>{table(data.accounts.filter((a) => a.ownerUserId === m.userId))}</Tabs.Panel>
            ))}
          </Tabs>
        </>
      )}
      <Modal opened={!!editing} onClose={() => setEditing(null)} title={editing?.name}>
        {editing && <DetailsForm account={editing} onDone={() => setEditing(null)} />}
      </Modal>
    </Stack>
  );
}
