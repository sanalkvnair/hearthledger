import { ActionIcon, Badge, Button, Card, Group, Modal, NumberInput, Progress, Select, SimpleGrid, Stack, Table, Tabs, Text, TextInput, Title } from '@mantine/core';
import { IconPlus, IconTrash } from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api';
import { isoDate, money } from '../format';
import { canWrite, useAccounts, useApiMutation, useCategories, useMe } from '../hooks';
import { LOAN_TYPES, type Loan, type LoanPreview, type LoanType, type ScheduleRow } from '../types';

const pct = (n: number | null | undefined) => (n == null ? '–' : `${Number(n).toFixed(2)}%`);

function LoanForm({ onDone }: { onDone: () => void }) {
  const { t, i18n } = useTranslation();
  const { data: me } = useMe();
  const { data: accounts = [] } = useAccounts();
  const { data: categories = [] } = useCategories();
  const [loanType, setLoanType] = useState<LoanType>('ANNUITY');
  const [name, setName] = useState('');
  const [lender, setLender] = useState('');
  const [currency, setCurrency] = useState(me!.family!.baseCurrency);
  const [principal, setPrincipal] = useState<number | string>('');
  const [rate, setRate] = useState<number | string>(0);
  const [tenure, setTenure] = useState<number | string>(12);
  const [emi, setEmi] = useState<number | string>('');
  const [amort, setAmort] = useState<number | string>(2);
  const [balloon, setBalloon] = useState<number | string>('');
  const [down, setDown] = useState<number | string>('');
  const [setup, setSetup] = useState<number | string>('');
  const [monthly, setMonthly] = useState<number | string>('');
  const [opening, setOpening] = useState<number | string>('');
  const [start, setStart] = useState(isoDate(new Date()));
  const [term, setTerm] = useState<string | null>(null);
  const [accountId, setAccountId] = useState<string | null>(null);
  const [categoryId, setCategoryId] = useState<string | null>(null);
  const [preview, setPreview] = useState<LoanPreview | null>(null);

  const needsTenure = loanType === 'ANNUITY' || loanType === 'BALLOON_ANNUITY';
  const num = (v: number | string) => (v === '' ? null : Number(v));
  const body = {
    name: name || 'Loan', lender: lender || null, currency, principal: Number(principal), interestRate: Number(rate), startDate: start, loanType,
    tenureMonths: needsTenure ? num(tenure) : null,
    emi: loanType === 'ANNUITY' ? num(emi) : null,
    amortizationRate: loanType === 'FIXED_AMORTIZATION' ? num(amort) : null,
    balloonAmount: loanType === 'BALLOON_ANNUITY' ? Number(balloon || 0) : 0,
    downPayment: loanType === 'BALLOON_ANNUITY' ? Number(down || 0) : 0,
    setupFee: loanType === 'BALLOON_ANNUITY' ? Number(setup || 0) : 0,
    monthlyFee: loanType === 'BALLOON_ANNUITY' ? Number(monthly || 0) : 0,
    openingBalance: num(opening), term,
    accountId: accountId ? Number(accountId) : null, categoryId: categoryId ? Number(categoryId) : null,
  };
  const key = JSON.stringify(body);
  const ready = Number(principal) > 0 && currency.length === 3 && (!needsTenure || Number(tenure) > 0);

  // live calculation while the form is filled in
  useEffect(() => {
    if (!ready) { setPreview(null); return; }
    const handle = setTimeout(() => {
      api<LoanPreview>('/loans/preview', 'POST', JSON.parse(key)).then(setPreview).catch(() => setPreview(null));
    }, 400);
    return () => clearTimeout(handle);
  }, [key, ready]);

  const save = useApiMutation(() => api('/loans', 'POST', body), ['loans', 'transactions', 'dashboard', 'buffer'], onDone);
  const fmt = (n: number) => money(n, currency, i18n.language);
  const numberInput = (label: string, value: number | string, set: (v: number | string) => void, extra: object = {}) => (
    <NumberInput label={label} value={value} onChange={set} min={0} decimalScale={2} {...extra} />
  );

  return (
    <Stack>
      <Select label={t('p5.loanType')} allowDeselect={false} data={LOAN_TYPES.map((l) => ({ value: l, label: t(`p5.${l}`) }))} value={loanType} onChange={(v) => setLoanType(v as LoanType)} />
      <Group grow>
        <TextInput label={t('common.name')} value={name} onChange={(e) => setName(e.currentTarget.value)} data-autofocus />
        <TextInput label={t('loans.lender')} value={lender} onChange={(e) => setLender(e.currentTarget.value)} />
      </Group>
      <Group grow>
        {numberInput(t('loans.principal'), principal, setPrincipal)}
        <TextInput label={t('common.currency')} maxLength={3} value={currency} onChange={(e) => setCurrency(e.currentTarget.value.toUpperCase())} />
      </Group>
      <Group grow>
        {numberInput(t('loans.rate'), rate, setRate)}
        {needsTenure && <NumberInput label={t('loans.tenure')} value={tenure} onChange={setTenure} min={1} />}
        {loanType === 'FIXED_AMORTIZATION' && numberInput(t('p5.amortRate'), amort, setAmort)}
        {loanType === 'ANNUITY' && numberInput(t('loans.emi'), emi, setEmi)}
      </Group>
      {loanType === 'BALLOON_ANNUITY' && (
        <SimpleGrid cols={{ base: 2, sm: 4 }}>
          {numberInput(t('p5.balloon'), balloon, setBalloon)}
          {numberInput(t('p5.downPayment'), down, setDown)}
          {numberInput(t('p5.setupFee'), setup, setSetup)}
          {numberInput(t('p5.monthlyFee'), monthly, setMonthly)}
        </SimpleGrid>
      )}
      <Group grow>
        {numberInput(t('p5.openingBalance'), opening, setOpening)}
        <TextInput type="date" label={t('loans.firstEmi')} value={start} onChange={(e) => setStart(e.currentTarget.value)} />
      </Group>
      <Select label={t('loans.term')} clearable placeholder="auto" data={[{ value: 'SHORT_TERM', label: t('loans.short') }, { value: 'LONG_TERM', label: t('loans.long') }]} value={term} onChange={setTerm} />
      <Group grow>
        <Select label={t('txn.account')} clearable searchable data={accounts.map((a) => ({ value: String(a.id), label: a.name }))} value={accountId} onChange={setAccountId} />
        <Select label={t('txn.category')} clearable searchable data={categories.filter((c) => c.type === 'EXPENSE').map((c) => ({ value: String(c.id), label: c.name }))} value={categoryId} onChange={setCategoryId} />
      </Group>
      {preview && (
        <Card withBorder bg="var(--mantine-color-default-hover)">
          <Text size="sm" fw={600} mb={4}>{t('p5.preview')}</Text>
          <SimpleGrid cols={{ base: 2, sm: 3 }} spacing="xs">
            <Text size="sm">{t('p5.financed')}: {fmt(preview.financed)}</Text>
            <Text size="sm">{t('loans.emi').split(' (')[0]}: {fmt(preview.firstPayment)}{preview.lastPayment !== preview.firstPayment && ` → ${fmt(preview.lastPayment)}`}</Text>
            <Text size="sm">{preview.openEnded ? t('p5.openEnded') : `${preview.payments} ×`}</Text>
            <Text size="sm">{t('p5.totalInterest')}: {fmt(preview.totalInterest)}</Text>
            <Text size="sm">{t('p5.totalFees')}: {fmt(preview.totalFees)}</Text>
            <Text size="sm">{t('p5.totalCost')}: {fmt(preview.totalCost)}</Text>
            {preview.balloon > 0 && <Text size="sm">{t('p5.balloon')}: {fmt(preview.balloon)}</Text>}
            {preview.effectiveRate != null && <Text size="sm" fw={600}>{t('p5.effective')}: {pct(preview.effectiveRate)}</Text>}
          </SimpleGrid>
        </Card>
      )}
      <Group justify="flex-end">
        <Button variant="default" onClick={onDone}>{t('common.cancel')}</Button>
        <Button loading={save.isPending} disabled={!name || !ready} onClick={() => save.mutate(undefined)}>{t('common.save')}</Button>
      </Group>
    </Stack>
  );
}

function Schedule({ loan }: { loan: Loan }) {
  const { t, i18n } = useTranslation();
  const { data = [] } = useQuery({ queryKey: ['loan-schedule', loan.id, loan.rates.length], queryFn: () => api<ScheduleRow[]>(`/loans/${loan.id}/schedule`) });
  const fmt = (n: number) => money(n, loan.currency, i18n.language);
  const hasFees = data.some((r) => r.fee > 0);
  return (
    <Table.ScrollContainer minWidth={600} h={400}>
      <Table stickyHeader striped>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>#</Table.Th>
            <Table.Th>{t('common.date')}</Table.Th>
            <Table.Th ta="right">{t('common.amount')}</Table.Th>
            <Table.Th ta="right">{t('loans.interest')}</Table.Th>
            <Table.Th ta="right">{t('p5.principalPart')}</Table.Th>
            {hasFees && <Table.Th ta="right">{t('p5.fee')}</Table.Th>}
            <Table.Th ta="right">{t('loans.balance')}</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {data.map((r) => (
            <Table.Tr key={r.number} c={r.number <= loan.paidInstalments ? 'dimmed' : undefined}>
              <Table.Td>{r.number}</Table.Td>
              <Table.Td>{r.date}</Table.Td>
              <Table.Td ta="right">{fmt(r.payment + r.fee)}</Table.Td>
              <Table.Td ta="right">{fmt(r.interest)}</Table.Td>
              <Table.Td ta="right">{fmt(r.principal)}</Table.Td>
              {hasFees && <Table.Td ta="right">{fmt(r.fee)}</Table.Td>}
              <Table.Td ta="right">{fmt(r.balance)}</Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Table.ScrollContainer>
  );
}

function Rates({ loan, writer }: { loan: Loan; writer: boolean }) {
  const { t } = useTranslation();
  const [from, setFrom] = useState(isoDate(new Date()));
  const [annual, setAnnual] = useState<number | string>('');
  const refresh = ['loans', 'loan-schedule', 'buffer'];
  const add = useApiMutation(() => api(`/loans/${loan.id}/rates`, 'POST', { effectiveFrom: from, annualRate: Number(annual) }), refresh, () => setAnnual(''));
  const remove = useApiMutation((id: number) => api(`/loans/${loan.id}/rates/${id}`, 'DELETE'), refresh);
  return (
    <Stack>
      <Table>
        <Table.Tbody>
          {loan.rates.map((r) => (
            <Table.Tr key={r.id}>
              <Table.Td>{r.effectiveFrom}</Table.Td>
              <Table.Td>{pct(r.annualRate)}</Table.Td>
              <Table.Td>{writer && <ActionIcon variant="subtle" color="red" aria-label={t('common.delete')} onClick={() => remove.mutate(r.id)}><IconTrash size={14} /></ActionIcon>}</Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
      {writer && (
        <Group align="flex-end">
          <TextInput type="date" label={t('p5.effectiveFrom')} value={from} onChange={(e) => setFrom(e.currentTarget.value)} />
          <NumberInput label={t('p5.newRate')} value={annual} onChange={setAnnual} min={0} decimalScale={3} />
          <Button loading={add.isPending} disabled={annual === ''} onClick={() => add.mutate(undefined)}>{t('common.add')}</Button>
        </Group>
      )}
    </Stack>
  );
}

export function LoansPage() {
  const { t, i18n } = useTranslation();
  const { data: me } = useMe();
  const { data: loans = [] } = useQuery({ queryKey: ['loans'], queryFn: () => api<Loan[]>('/loans') });
  const [creating, setCreating] = useState(false);
  const [viewingId, setViewingId] = useState<number | null>(null);
  const viewing = loans.find((l) => l.id === viewingId) ?? null;
  const close = useApiMutation((id: number) => api(`/loans/${id}`, 'DELETE'), ['loans', 'buffer']);
  const writer = canWrite(me?.role);

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={2}>{t('loans.title')}</Title>
        {writer && <Button leftSection={<IconPlus size={16} />} onClick={() => setCreating(true)}>{t('loans.new')}</Button>}
      </Group>
      <Table.ScrollContainer minWidth={900}>
        <Table striped>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('common.name')}</Table.Th>
              <Table.Th>{t('p5.loanType')}</Table.Th>
              <Table.Th ta="right">{t('p5.nextPayment')}</Table.Th>
              <Table.Th ta="right">{t('loans.remaining')}</Table.Th>
              <Table.Th>{t('loans.rate')}</Table.Th>
              <Table.Th w={200}>{t('loans.paid')}</Table.Th>
              <Table.Th>{t('loans.ends')}</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {loans.map((l) => (
              <Table.Tr key={l.id} c={l.active ? undefined : 'dimmed'}>
                <Table.Td>
                  {l.name}{l.lender && <Text size="xs" c="dimmed">{l.lender}</Text>}
                  <Badge variant="light" size="xs" color={l.term === 'LONG_TERM' ? 'indigo' : 'orange'}>{t(l.term === 'LONG_TERM' ? 'loans.long' : 'loans.short')}</Badge>
                </Table.Td>
                <Table.Td>{t(`p5.${l.loanType}`)}</Table.Td>
                <Table.Td ta="right">{l.nextPayment != null ? money(l.nextPayment, l.currency, i18n.language) : '–'}</Table.Td>
                <Table.Td ta="right">{money(l.remainingBalance, l.currency, i18n.language)}</Table.Td>
                <Table.Td>
                  {pct(l.interestRate)}
                  {l.effectiveRate != null && <Text size="xs" c="dimmed">{t('p5.effective')} {pct(l.effectiveRate)}</Text>}
                </Table.Td>
                <Table.Td>
                  {l.tenureMonths != null ? (
                    <>
                      <Progress value={(l.paidInstalments / l.tenureMonths) * 100} />
                      <Text size="xs" c="dimmed">{l.paidInstalments}/{l.tenureMonths} · {l.remainingInstalments} {t('loans.left')}</Text>
                    </>
                  ) : <Text size="xs" c="dimmed">{l.paidInstalments} · {t('p5.openEnded')}</Text>}
                </Table.Td>
                <Table.Td>{l.endDate ?? ''}</Table.Td>
                <Table.Td>
                  <Group gap={4} wrap="nowrap">
                    <Button size="compact-xs" variant="subtle" onClick={() => setViewingId(l.id)}>{t('loans.schedule')}</Button>
                    {writer && l.active && <Button size="compact-xs" variant="subtle" color="red" onClick={() => confirm(t('loans.close') + '?') && close.mutate(l.id)}>{t('loans.close')}</Button>}
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Table.ScrollContainer>
      <Modal opened={creating} onClose={() => setCreating(false)} title={t('loans.new')} size="xl">
        {creating && <LoanForm onDone={() => setCreating(false)} />}
      </Modal>
      <Modal opened={!!viewing} onClose={() => setViewingId(null)} title={viewing?.name} size="xl">
        {viewing && (
          <Tabs defaultValue="schedule">
            <Tabs.List mb="md">
              <Tabs.Tab value="schedule">{t('loans.schedule')}</Tabs.Tab>
              {viewing.loanType !== 'BALLOON_ANNUITY' && <Tabs.Tab value="rates">{t('p5.rateChanges')}</Tabs.Tab>}
            </Tabs.List>
            <Tabs.Panel value="schedule"><Schedule loan={viewing} /></Tabs.Panel>
            <Tabs.Panel value="rates"><Rates loan={viewing} writer={writer} /></Tabs.Panel>
          </Tabs>
        )}
      </Modal>
    </Stack>
  );
}
