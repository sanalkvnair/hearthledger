import { Badge, Button, Group, Modal, NumberInput, Progress, Select, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { IconPlus } from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api';
import { isoDate, money } from '../format';
import { canWrite, useAccounts, useApiMutation, useCategories, useMe } from '../hooks';
import type { Loan, ScheduleRow } from '../types';

function LoanForm({ onDone }: { onDone: () => void }) {
  const { t } = useTranslation();
  const { data: me } = useMe();
  const { data: accounts = [] } = useAccounts();
  const { data: categories = [] } = useCategories();
  const [name, setName] = useState('');
  const [lender, setLender] = useState('');
  const [currency, setCurrency] = useState(me!.family!.baseCurrency);
  const [principal, setPrincipal] = useState<number | string>('');
  const [rate, setRate] = useState<number | string>(0);
  const [tenure, setTenure] = useState<number | string>(12);
  const [emi, setEmi] = useState<number | string>('');
  const [start, setStart] = useState(isoDate(new Date()));
  const [term, setTerm] = useState<string | null>(null);
  const [accountId, setAccountId] = useState<string | null>(null);
  const [categoryId, setCategoryId] = useState<string | null>(null);
  const save = useApiMutation(
    () => api('/loans', 'POST', {
      name, lender: lender || null, currency, principal: Number(principal), interestRate: Number(rate), tenureMonths: Number(tenure),
      emi: emi === '' ? null : Number(emi), startDate: start, term, accountId: accountId ? Number(accountId) : null,
      categoryId: categoryId ? Number(categoryId) : null,
    }),
    ['loans', 'transactions', 'dashboard', 'buffer'],
    onDone,
  );
  return (
    <Stack>
      <Group grow>
        <TextInput label={t('common.name')} value={name} onChange={(e) => setName(e.currentTarget.value)} data-autofocus />
        <TextInput label={t('loans.lender')} value={lender} onChange={(e) => setLender(e.currentTarget.value)} />
      </Group>
      <Group grow>
        <NumberInput label={t('loans.principal')} value={principal} onChange={setPrincipal} min={0} decimalScale={2} />
        <TextInput label={t('common.currency')} maxLength={3} value={currency} onChange={(e) => setCurrency(e.currentTarget.value.toUpperCase())} />
      </Group>
      <Group grow>
        <NumberInput label={t('loans.rate')} value={rate} onChange={setRate} min={0} decimalScale={2} />
        <NumberInput label={t('loans.tenure')} value={tenure} onChange={setTenure} min={1} />
      </Group>
      <Group grow>
        <NumberInput label={t('loans.emi')} value={emi} onChange={setEmi} min={0} decimalScale={2} />
        <TextInput type="date" label={t('loans.firstEmi')} value={start} onChange={(e) => setStart(e.currentTarget.value)} />
      </Group>
      <Select label={t('loans.term')} clearable placeholder="auto" data={[{ value: 'SHORT_TERM', label: t('loans.short') }, { value: 'LONG_TERM', label: t('loans.long') }]} value={term} onChange={setTerm} />
      <Select label={t('txn.account')} clearable searchable data={accounts.map((a) => ({ value: String(a.id), label: a.name }))} value={accountId} onChange={setAccountId} />
      <Select label={t('txn.category')} clearable searchable data={categories.filter((c) => c.type === 'EXPENSE').map((c) => ({ value: String(c.id), label: c.name }))} value={categoryId} onChange={setCategoryId} />
      <Group justify="flex-end">
        <Button variant="default" onClick={onDone}>{t('common.cancel')}</Button>
        <Button loading={save.isPending} disabled={!name || !principal || currency.length !== 3} onClick={() => save.mutate(undefined)}>{t('common.save')}</Button>
      </Group>
    </Stack>
  );
}

function Schedule({ loan }: { loan: Loan }) {
  const { t, i18n } = useTranslation();
  const { data = [] } = useQuery({ queryKey: ['loan-schedule', loan.id], queryFn: () => api<ScheduleRow[]>(`/loans/${loan.id}/schedule`) });
  const fmt = (n: number) => money(n, loan.currency, i18n.language);
  return (
    <Table.ScrollContainer minWidth={500} h={400}>
      <Table stickyHeader striped>
        <Table.Thead>
          <Table.Tr><Table.Th>#</Table.Th><Table.Th>{t('common.date')}</Table.Th><Table.Th ta="right">{t('common.amount')}</Table.Th><Table.Th ta="right">{t('loans.interest')}</Table.Th><Table.Th ta="right">{t('loans.balance')}</Table.Th></Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {data.map((r) => (
            <Table.Tr key={r.number} c={r.number <= loan.paidInstalments ? 'dimmed' : undefined}>
              <Table.Td>{r.number}</Table.Td><Table.Td>{r.date}</Table.Td><Table.Td ta="right">{fmt(r.payment)}</Table.Td><Table.Td ta="right">{fmt(r.interest)}</Table.Td><Table.Td ta="right">{fmt(r.balance)}</Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Table.ScrollContainer>
  );
}

export function LoansPage() {
  const { t, i18n } = useTranslation();
  const { data: me } = useMe();
  const { data: loans = [] } = useQuery({ queryKey: ['loans'], queryFn: () => api<Loan[]>('/loans') });
  const [creating, setCreating] = useState(false);
  const [viewing, setViewing] = useState<Loan | null>(null);
  const close = useApiMutation((id: number) => api(`/loans/${id}`, 'DELETE'), ['loans', 'buffer']);

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={2}>{t('loans.title')}</Title>
        {canWrite(me?.role) && <Button leftSection={<IconPlus size={16} />} onClick={() => setCreating(true)}>{t('loans.new')}</Button>}
      </Group>
      <Table.ScrollContainer minWidth={800}>
        <Table striped>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('common.name')}</Table.Th>
              <Table.Th>{t('loans.term')}</Table.Th>
              <Table.Th ta="right">{t('loans.emi').split(' (')[0]}</Table.Th>
              <Table.Th ta="right">{t('loans.remaining')}</Table.Th>
              <Table.Th w={200}>{t('loans.paid')}</Table.Th>
              <Table.Th>{t('loans.ends')}</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {loans.map((l) => (
              <Table.Tr key={l.id} c={l.active ? undefined : 'dimmed'}>
                <Table.Td>{l.name}{l.lender && <Text size="xs" c="dimmed">{l.lender}</Text>}</Table.Td>
                <Table.Td><Badge variant="light" color={l.term === 'LONG_TERM' ? 'indigo' : 'orange'}>{t(l.term === 'LONG_TERM' ? 'loans.long' : 'loans.short')}</Badge></Table.Td>
                <Table.Td ta="right">{money(l.emi, l.currency, i18n.language)}</Table.Td>
                <Table.Td ta="right">{money(l.remainingBalance, l.currency, i18n.language)}</Table.Td>
                <Table.Td>
                  <Progress value={(l.paidInstalments / l.tenureMonths) * 100} />
                  <Text size="xs" c="dimmed">{l.paidInstalments}/{l.tenureMonths} · {l.remainingInstalments} {t('loans.left')}</Text>
                </Table.Td>
                <Table.Td>{l.endDate}</Table.Td>
                <Table.Td>
                  <Group gap={4} wrap="nowrap">
                    <Button size="compact-xs" variant="subtle" onClick={() => setViewing(l)}>{t('loans.schedule')}</Button>
                    {canWrite(me?.role) && l.active && <Button size="compact-xs" variant="subtle" color="red" onClick={() => confirm(t('loans.close') + '?') && close.mutate(l.id)}>{t('loans.close')}</Button>}
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Table.ScrollContainer>
      <Modal opened={creating} onClose={() => setCreating(false)} title={t('loans.new')} size="lg">
        {creating && <LoanForm onDone={() => setCreating(false)} />}
      </Modal>
      <Modal opened={!!viewing} onClose={() => setViewing(null)} title={viewing?.name} size="xl">
        {viewing && <Schedule loan={viewing} />}
      </Modal>
    </Stack>
  );
}
