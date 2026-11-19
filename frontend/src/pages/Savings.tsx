import { LineChart } from '@mantine/charts';
import { ActionIcon, Badge, Button, Card, Group, Modal, NumberInput, SegmentedControl, SimpleGrid, Stack, Table, Tabs, Text, TextInput, Title } from '@mantine/core';
import { IconTrash } from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api';
import { isoDate, money, shortDate } from '../format';
import { canWrite, useApiMutation, useMe } from '../hooks';
import type { HistoryPoint, SavingsAccount, SavingsOverview, Valuation } from '../types';

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

function Valuations({ account, onClose }: { account: SavingsAccount; onClose: () => void }) {
  const { t, i18n } = useTranslation();
  const { data: me } = useMe();
  const fmt = (n: number) => money(n, account.currency, i18n.language);
  const { data: list = [] } = useQuery({ queryKey: ['savings', 'valuations', account.accountId], queryFn: () => api<Valuation[]>(`/savings/${account.accountId}/valuations`) });
  const { data: history } = useQuery({ queryKey: ['savings', 'history', account.accountId], queryFn: () => api<{ points: HistoryPoint[] }>(`/savings/history?accountId=${account.accountId}`) });
  const [on, setOn] = useState(isoDate(new Date()));
  const [value, setValue] = useState<number | string>('');
  const [pl, setPl] = useState<number | string>('');
  const refresh = ['savings', 'buffer'];
  const add = useApiMutation(
    () => api(`/savings/${account.accountId}/valuations`, 'POST', { valuedOn: on, marketValue: value === '' ? null : Number(value), profitLoss: pl === '' ? null : Number(pl) }),
    refresh,
    () => { setValue(''); setPl(''); },
  );
  const remove = useApiMutation((id: number) => api(`/savings/${account.accountId}/valuations/${id}`, 'DELETE'), refresh);
  const writer = canWrite(me?.role);
  return (
    <Stack>
      {history && history.points.length > 1 && (
        <LineChart
          h={220} dataKey="label" withLegend
          data={history.points.map((p) => ({ label: shortDate(p.date, i18n.language), invested: p.invested, value: p.value }))}
          series={[{ name: 'invested', label: t('p5.invested'), color: 'gray.6' }, { name: 'value', label: t('p5.valueLabel'), color: 'teal.6' }]}
        />
      )}
      {writer && (
        <Stack gap="xs">
          <Text size="xs" c="dimmed">{t('p5.orEnter')}</Text>
          <Group grow align="flex-end">
            <TextInput type="date" label={t('common.date')} value={on} onChange={(e) => setOn(e.currentTarget.value)} />
            <NumberInput label={t('p5.marketValue')} value={value} onChange={(v) => { setValue(v); if (v !== '') setPl(''); }} decimalScale={2} />
            <NumberInput label={t('p5.profitLoss')} value={pl} onChange={(v) => { setPl(v); if (v !== '') setValue(''); }} decimalScale={2} />
            <Button loading={add.isPending} disabled={value === '' && pl === ''} onClick={() => add.mutate(undefined)}>{t('common.add')}</Button>
          </Group>
        </Stack>
      )}
      <Table striped>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{t('common.date')}</Table.Th>
            <Table.Th ta="right">{t('p5.invested')}</Table.Th>
            <Table.Th ta="right">{t('p5.marketValue')}</Table.Th>
            <Table.Th ta="right">{t('p5.profitLoss')}</Table.Th>
            <Table.Th />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {list.map((v) => (
            <Table.Tr key={v.id}>
              <Table.Td>{v.valuedOn}</Table.Td>
              <Table.Td ta="right">{fmt(v.invested)}</Table.Td>
              <Table.Td ta="right">{fmt(v.marketValue)}</Table.Td>
              <Table.Td ta="right"><Text span c={v.profitLoss < 0 ? 'red' : 'teal'}>{fmt(v.profitLoss)}{v.profitLossPercent != null && ` (${v.profitLossPercent}%)`}</Text></Table.Td>
              <Table.Td>{writer && <ActionIcon variant="subtle" color="red" aria-label={t('common.delete')} onClick={() => remove.mutate(v.id)}><IconTrash size={14} /></ActionIcon>}</Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
      <Group justify="flex-end"><Button variant="default" onClick={onClose}>{t('common.cancel')}</Button></Group>
    </Stack>
  );
}

export function SavingsPage() {
  const { t, i18n } = useTranslation();
  const { data: me } = useMe();
  const { data } = useQuery({ queryKey: ['savings'], queryFn: () => api<SavingsOverview>('/savings') });
  const [editing, setEditing] = useState<SavingsAccount | null>(null);
  const [valuing, setValuing] = useState<SavingsAccount | null>(null);
  const [scope, setScope] = useState('ALL');
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
              <Table.Td>
                {a.ownership === 'PRIVATE' ? a.ownerNames.join(', ') : <Badge variant="light" color={a.ownership === 'JOINT' ? 'grape' : 'blue'}>{t(`p5.${a.ownership}`)}</Badge>}
                {a.ownership === 'JOINT' && <Text size="xs" c="dimmed">{a.ownerNames.join(', ')}</Text>}
              </Table.Td>
              <Table.Td ta="right">{fmt(a.contributed)}</Table.Td>
              <Table.Td ta="right">{fmt(a.currentValue)}</Table.Td>
              <Table.Td ta="right"><Text c={a.gain < 0 ? 'red' : 'teal'} span>{fmt(a.gain)}</Text></Table.Td>
              <Table.Td>{a.interestRate != null ? `${a.interestRate}%` : ''}</Table.Td>
              <Table.Td>{a.maturityDate ?? ''}{a.maturityAmount != null && ` (${money(a.maturityAmount, a.currency, i18n.language)})`}</Table.Td>
              <Table.Td>
                <Group gap={4} wrap="nowrap">
                  {a.type === 'INVESTMENT' && <Button size="compact-xs" variant="subtle" onClick={() => setValuing(a)}>{t('p5.valuations')}</Button>}
                  {canWrite(me?.role) && <Button size="compact-xs" variant="subtle" onClick={() => setEditing(a)}>{t('savingsPage.details')}</Button>}
                </Group>
              </Table.Td>
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

  const visible = data.accounts.filter((a) => scope === 'ALL' || (scope === 'SHARED' ? a.ownership !== 'PRIVATE' : a.ownership === 'PRIVATE'));
  const members = data.byMember;
  return (
    <Stack>
      <Group justify="space-between">
        <Title order={2}>{t('savingsPage.title')}</Title>
        <SegmentedControl value={scope} onChange={setScope} data={[{ value: 'ALL', label: t('common.all') }, { value: 'SHARED', label: t('p5.shared') }, { value: 'INDIVIDUAL', label: t('p5.individual') }]} />
      </Group>
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
            <Tabs.Panel value="all">{table(visible)}</Tabs.Panel>
            {members.filter((m) => m.userId !== null).map((m) => (
              <Tabs.Panel key={m.userId} value={String(m.userId)}>{table(visible.filter((a) => a.ownerIds.includes(m.userId!)))}</Tabs.Panel>
            ))}
          </Tabs>
        </>
      )}
      <Modal opened={!!valuing} onClose={() => setValuing(null)} title={valuing?.name} size="xl">
        {valuing && <Valuations account={valuing} onClose={() => setValuing(null)} />}
      </Modal>
      <Modal opened={!!editing} onClose={() => setEditing(null)} title={editing?.name}>
        {editing && <DetailsForm account={editing} onDone={() => setEditing(null)} />}
      </Modal>
    </Stack>
  );
}
