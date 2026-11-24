import { ActionIcon, Badge, Button, Card, Checkbox, Group, Modal, NumberInput, Progress, SegmentedControl, Select, SimpleGrid, Stack, Table, Tabs, Text, TextInput, Title } from '@mantine/core';
import { IconPlus, IconTrash } from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api';
import { isoDate, money } from '../format';
import { canWrite, useAccounts, useApiMutation, useCategories, useMe } from '../hooks';
import { LOAN_TYPES, type Loan, type LoanPreview, type LoanType, type ScheduleRow } from '../types';

const pct = (n: number | null | undefined) => (n == null ? '–' : `${Number(n).toFixed(2)}%`);
const LOAN_KEYS = ['loans', 'loan-schedule', 'transactions', 'dashboard', 'buffer'];

function useDuration() {
  const { t } = useTranslation();
  return (months: number) => {
    const y = Math.floor(months / 12);
    const m = months % 12;
    return [y > 0 ? `${y} ${t('loans.years')}` : '', m > 0 ? `${m} ${t('loans.months')}` : ''].filter(Boolean).join(' ') || `0 ${t('loans.months')}`;
  };
}

function Stat({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div>
      <Text size="xs" c="dimmed">{label}</Text>
      <Text size="sm" fw={500}>{value}</Text>
      {sub && <Text size="xs" c="dimmed">{sub}</Text>}
    </div>
  );
}

function LoanForm({ loan, onDone }: { loan?: Loan; onDone: () => void }) {
  const { t, i18n } = useTranslation();
  const duration = useDuration();
  const { data: me } = useMe();
  const { data: accounts = [] } = useAccounts();
  const { data: categories = [] } = useCategories();
  const [loanType, setLoanType] = useState<LoanType>(loan?.loanType ?? 'ANNUITY');
  const [name, setName] = useState(loan?.name ?? '');
  const [lender, setLender] = useState(loan?.lender ?? '');
  const [currency, setCurrency] = useState(loan?.currency ?? me!.family!.baseCurrency);
  const [principal, setPrincipal] = useState<number | string>(loan?.principal ?? '');
  const [rate, setRate] = useState<number | string>(loan?.initialRate ?? 0);
  const [fixedUntil, setFixedUntil] = useState(loan?.rateFixedUntil ?? '');
  const [years, setYears] = useState<number | string>(loan?.termMonths ? Math.floor(loan.termMonths / 12) : '');
  const [months, setMonths] = useState<number | string>(loan?.termMonths ? loan.termMonths % 12 : '');
  const [emi, setEmi] = useState<number | string>('');
  const [amortMode, setAmortMode] = useState<string>(loan?.amortizationRate != null ? 'percent' : 'amount');
  const [amortAmount, setAmortAmount] = useState<number | string>(loan?.amortizationAmount ?? '');
  const [amortPercent, setAmortPercent] = useState<number | string>(loan?.amortizationRate ?? '');
  const [balloon, setBalloon] = useState<number | string>(loan?.balloonAmount || '');
  const [down, setDown] = useState<number | string>(loan?.downPayment || '');
  const [setup, setSetup] = useState<number | string>(loan?.setupFee || '');
  const [monthly, setMonthly] = useState<number | string>(loan?.monthlyFee || '');
  const [opening, setOpening] = useState<number | string>(loan?.openingBalance ?? '');
  const [disbursed, setDisbursed] = useState(loan?.disbursedOn ?? '');
  const [start, setStart] = useState(loan?.startDate ?? isoDate(new Date()));
  const [term, setTerm] = useState<string | null>(loan?.term ?? null);
  const [accountId, setAccountId] = useState<string | null>(loan?.accountId != null ? String(loan.accountId) : null);
  const [categoryId, setCategoryId] = useState<string | null>(loan?.categoryId != null ? String(loan.categoryId) : null);
  const [preview, setPreview] = useState<LoanPreview | null>(null);

  const needsTenure = loanType === 'ANNUITY' || loanType === 'BALLOON_ANNUITY';
  const num = (v: number | string) => (v === '' ? null : Number(v));
  const termMonths = Number(years || 0) * 12 + Number(months || 0);
  const body = {
    name: name || 'Loan', lender: lender || null, currency, principal: Number(principal), interestRate: Number(rate), startDate: start, loanType,
    disbursedOn: disbursed || null, rateFixedUntil: fixedUntil || null,
    tenureMonths: termMonths > 0 ? termMonths : null,
    emi: loanType === 'ANNUITY' ? num(emi) : null,
    amortizationRate: loanType === 'FIXED_AMORTIZATION' && amortMode === 'percent' ? num(amortPercent) : null,
    amortizationAmount: loanType === 'FIXED_AMORTIZATION' && amortMode === 'amount' ? num(amortAmount) : null,
    balloonAmount: loanType === 'BALLOON_ANNUITY' ? Number(balloon || 0) : 0,
    downPayment: loanType === 'BALLOON_ANNUITY' ? Number(down || 0) : 0,
    setupFee: loanType === 'BALLOON_ANNUITY' ? Number(setup || 0) : 0,
    monthlyFee: loanType === 'BALLOON_ANNUITY' ? Number(monthly || 0) : 0,
    openingBalance: num(opening), term, ownerUserId: loan?.ownerUserId ?? null,
    accountId: accountId ? Number(accountId) : null, categoryId: categoryId ? Number(categoryId) : null,
  };
  const key = JSON.stringify(body);
  const amortGiven = (amortMode === 'amount' ? Number(amortAmount) : Number(amortPercent)) > 0;
  const ready = Number(principal) > 0 && currency.length === 3 && (!needsTenure || termMonths > 0) && (loanType !== 'FIXED_AMORTIZATION' || amortGiven || termMonths > 0);

  // live calculation while the form is filled in
  useEffect(() => {
    if (!ready) { setPreview(null); return; }
    const handle = setTimeout(() => {
      api<LoanPreview>('/loans/preview', 'POST', JSON.parse(key)).then(setPreview).catch(() => setPreview(null));
    }, 400);
    return () => clearTimeout(handle);
  }, [key, ready]);

  const save = useApiMutation(() => (loan ? api(`/loans/${loan.id}`, 'PUT', body) : api('/loans', 'POST', body)), LOAN_KEYS, onDone);
  const fmt = (n: number) => money(n, currency, i18n.language);
  const numberInput = (label: string, value: number | string, set: (v: number | string) => void, extra: object = {}) => (
    <NumberInput label={label} value={value} onChange={set} min={0} decimalScale={2} {...extra} />
  );

  return (
    <Stack>
      {loan && <Text size="sm" c="dimmed">{t('loans.editNote')}</Text>}
      <Select label={t('p5.loanType')} allowDeselect={false} data={LOAN_TYPES.map((l) => ({ value: l, label: t(`p5.${l}`) }))} value={loanType} onChange={(v) => setLoanType(v as LoanType)} />
      <Group grow>
        <TextInput label={t('common.name')} value={name} onChange={(e) => setName(e.currentTarget.value)} data-autofocus />
        <TextInput label={t('loans.lender')} value={lender} onChange={(e) => setLender(e.currentTarget.value)} />
      </Group>
      <Group grow>
        {numberInput(t('loans.principal'), principal, setPrincipal)}
        <TextInput label={t('common.currency')} maxLength={3} value={currency} onChange={(e) => setCurrency(e.currentTarget.value.toUpperCase())} />
      </Group>
      <Group grow align="flex-end">
        {numberInput(loan ? t('loans.initialRate') + ' %' : t('loans.rate'), rate, setRate, { decimalScale: 3 })}
        <TextInput type="date" label={t('loans.fixedUntil')} value={fixedUntil} onChange={(e) => setFixedUntil(e.currentTarget.value)} />
      </Group>
      <Group grow align="flex-end">
        <NumberInput label={needsTenure ? t('loans.termYears') : `${t('loans.termYears')} (${t('loans.termOptional')})`} value={years} onChange={setYears} min={0} allowDecimal={false} />
        <NumberInput label={t('loans.termMonths')} value={months} onChange={setMonths} min={0} max={11} allowDecimal={false} />
        {loanType === 'ANNUITY' && numberInput(t('loans.emi'), emi, setEmi)}
      </Group>
      {loanType === 'FIXED_AMORTIZATION' && (
        <Stack gap={4}>
          <Text size="sm" fw={500}>{t('loans.amortMode')}</Text>
          <Group align="flex-end">
            <SegmentedControl value={amortMode} onChange={setAmortMode} data={[{ value: 'amount', label: t('loans.amortAmount') }, { value: 'percent', label: t('loans.amortPercent') }]} />
            {amortMode === 'amount' ? numberInput('', amortAmount, setAmortAmount, { 'aria-label': t('loans.amortAmount') }) : numberInput('', amortPercent, setAmortPercent, { 'aria-label': t('loans.amortPercent'), decimalScale: 3 })}
          </Group>
          <Text size="xs" c="dimmed">{t('loans.amortAuto')}</Text>
        </Stack>
      )}
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
        <TextInput type="date" label={t('loans.disbursed')} value={disbursed} onChange={(e) => setDisbursed(e.currentTarget.value)} />
        <TextInput type="date" label={t('loans.firstEmi')} value={start} onChange={(e) => setStart(e.currentTarget.value)} />
      </Group>
      <Select label={t('loans.term')} clearable placeholder="auto" data={[{ value: 'SHORT_TERM', label: t('loans.short') }, { value: 'LONG_TERM', label: t('loans.long') }]} value={term} onChange={setTerm} />
      <Group grow>
        <Select label={t('txn.account')} clearable searchable data={accounts.map((a) => ({ value: String(a.id), label: a.name }))} value={accountId} onChange={setAccountId} />
        <Select label={t('txn.category')} clearable searchable data={categories.filter((c) => c.type === 'EXPENSE').map((c) => ({ value: String(c.id), label: c.name }))} value={categoryId} onChange={setCategoryId} />
      </Group>
      {preview && (
        <Card withBorder bg="var(--mantine-color-default-hover)">
          <Text size="sm" fw={600} mb="xs">{t('p5.preview')}</Text>
          <SimpleGrid cols={{ base: 2, sm: 3 }} spacing="sm">
            <Stat label={t('p5.financed')} value={fmt(preview.financed)} />
            <Stat
              label={t('loans.firstPayment')} value={`${fmt(preview.firstPayment)} · ${preview.firstDate}`}
              sub={`${t('loans.interestPart')} ${fmt(preview.firstInterest)} + ${t('p5.principalPart').toLowerCase()} ${fmt(preview.firstPrincipal)}`}
            />
            <Stat
              label={t('loans.payments')}
              value={preview.openEnded ? t('p5.openEnded') : t('loans.previewPayments', { count: preview.payments, duration: duration(preview.payments) })}
              sub={preview.lastDate ? `${t('loans.lastPayment')} ${preview.lastDate} · ${fmt(preview.lastPayment)}` : undefined}
            />
            {preview.monthlyAmortization != null && <Stat label={t('loans.monthlyAmort')} value={fmt(preview.monthlyAmortization)} />}
            {preview.openEnded
              ? <Stat label={t('loans.interestPerYear')} value={fmt(preview.firstInterest * 12)} />
              : <Stat label={t('p5.totalInterest')} value={fmt(preview.totalInterest)} />}
            {preview.totalFees > 0 && <Stat label={t('p5.totalFees')} value={fmt(preview.totalFees)} />}
            {!preview.openEnded && <Stat label={t('p5.totalCost')} value={fmt(preview.totalCost)} />}
            {preview.balloon > 0 && <Stat label={t('p5.balloon')} value={fmt(preview.balloon)} />}
            {preview.effectiveRate != null && <Stat label={t('p5.effective')} value={pct(preview.effectiveRate)} />}
          </SimpleGrid>
          {!preview.openEnded && termMonths > 0 && preview.payments < termMonths && (
            <Text size="xs" c="dimmed" mt="xs">{t('loans.paidEarly', { duration: duration(termMonths) })}</Text>
          )}
          <Table mt="sm" fz="xs">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>#</Table.Th>
                <Table.Th>{t('common.date')}</Table.Th>
                <Table.Th ta="right">{t('common.amount')}</Table.Th>
                <Table.Th ta="right">{t('loans.interest')}</Table.Th>
                <Table.Th ta="right">{t('p5.principalPart')}</Table.Th>
                <Table.Th ta="right">{t('loans.balance')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {preview.firstRows.slice(0, 6).map((r) => (
                <Table.Tr key={r.number}>
                  <Table.Td>{r.number}</Table.Td>
                  <Table.Td>{r.date}</Table.Td>
                  <Table.Td ta="right">{fmt(r.payment + r.fee)}</Table.Td>
                  <Table.Td ta="right">{fmt(r.interest)}</Table.Td>
                  <Table.Td ta="right">{fmt(r.principal)}</Table.Td>
                  <Table.Td ta="right">{fmt(r.balance)}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
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
  const [fixed, setFixed] = useState('');
  const add = useApiMutation(() => api(`/loans/${loan.id}/rates`, 'POST', { effectiveFrom: from, annualRate: Number(annual), fixedUntil: fixed || null }), LOAN_KEYS, () => { setAnnual(''); setFixed(''); });
  const remove = useApiMutation((id: number) => api(`/loans/${loan.id}/rates/${id}`, 'DELETE'), LOAN_KEYS);
  const fixation = (until: string | null) => (until ? t('loans.fixedUntilShort', { date: until }) : t('loans.flexible'));
  return (
    <Stack>
      <Table>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{t('p5.effectiveFrom')}</Table.Th>
            <Table.Th>{t('loans.rate')}</Table.Th>
            <Table.Th>{t('p5.effective')}</Table.Th>
            <Table.Th />
            <Table.Th />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          <Table.Tr c="dimmed">
            <Table.Td>{loan.startDate} ({t('loans.initialRate').toLowerCase()})</Table.Td>
            <Table.Td>{pct(loan.initialRate)}</Table.Td>
            <Table.Td>{pct(loan.initialEffectiveRate)}</Table.Td>
            <Table.Td>{fixation(loan.rateFixedUntil)}</Table.Td>
            <Table.Td />
          </Table.Tr>
          {loan.rates.map((r) => (
            <Table.Tr key={r.id}>
              <Table.Td>{r.effectiveFrom}</Table.Td>
              <Table.Td>{pct(r.annualRate)}</Table.Td>
              <Table.Td>{pct(r.effectiveRate)}</Table.Td>
              <Table.Td>{fixation(r.fixedUntil)}</Table.Td>
              <Table.Td>{writer && <ActionIcon variant="subtle" color="red" aria-label={t('common.delete')} onClick={() => remove.mutate(r.id)}><IconTrash size={14} /></ActionIcon>}</Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
      {writer && (
        <Group align="flex-end">
          <TextInput type="date" label={t('p5.effectiveFrom')} value={from} onChange={(e) => setFrom(e.currentTarget.value)} />
          <NumberInput label={t('p5.newRate')} value={annual} onChange={setAnnual} min={0} decimalScale={3} />
          <TextInput type="date" label={t('loans.fixedUntil')} value={fixed} onChange={(e) => setFixed(e.currentTarget.value)} />
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
  const [editingId, setEditingId] = useState<number | null>(null);
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [deletePayments, setDeletePayments] = useState(true);
  const [viewingId, setViewingId] = useState<number | null>(null);
  const viewing = loans.find((l) => l.id === viewingId) ?? null;
  const editing = loans.find((l) => l.id === editingId) ?? null;
  const deleting = loans.find((l) => l.id === deletingId) ?? null;
  const close = useApiMutation((id: number) => api(`/loans/${id}/close`, 'POST'), LOAN_KEYS);
  const reopen = useApiMutation((id: number) => api(`/loans/${id}/reopen`, 'POST'), LOAN_KEYS);
  const remove = useApiMutation((id: number) => api(`/loans/${id}?deletePayments=${deletePayments}`, 'DELETE'), LOAN_KEYS, () => setDeletingId(null));
  const writer = canWrite(me?.role);
  const fmtFor = (l: Loan, n: number) => money(n, l.currency, i18n.language);

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
                <Table.Td ta="right">
                  {l.nextPayment != null ? fmtFor(l, l.nextPayment) : '–'}
                  {l.nextPayment != null && l.nextPaymentDate && (
                    <Text size="xs" c="dimmed">
                      {l.nextPaymentDate} · {t('loans.instalment')} {fmtFor(l, l.nextPrincipal ?? 0)} + {t('loans.interestPart').toLowerCase()} {fmtFor(l, l.nextInterest ?? 0)}
                    </Text>
                  )}
                </Table.Td>
                <Table.Td ta="right">{fmtFor(l, l.remainingBalance)}</Table.Td>
                <Table.Td>
                  {pct(l.interestRate)}
                  {l.effectiveRate != null && <Text size="xs" c="dimmed">{t('p5.effective')} {pct(l.effectiveRate)}</Text>}
                  <Text size="xs" c="dimmed">{l.currentRateFixedUntil ? t('loans.fixedUntilShort', { date: l.currentRateFixedUntil }) : t('loans.flexible')}</Text>
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
                    {writer && <Button size="compact-xs" variant="subtle" onClick={() => setEditingId(l.id)}>{t('common.edit')}</Button>}
                    {writer && l.active && <Button size="compact-xs" variant="subtle" color="orange" onClick={() => confirm(t('loans.close') + '?') && close.mutate(l.id)}>{t('loans.close')}</Button>}
                    {writer && !l.active && <Button size="compact-xs" variant="subtle" onClick={() => reopen.mutate(l.id)}>{t('loans.reopen')}</Button>}
                    {writer && <Button size="compact-xs" variant="subtle" color="red" onClick={() => { setDeletePayments(true); setDeletingId(l.id); }}>{t('common.delete')}</Button>}
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
      <Modal opened={!!editing} onClose={() => setEditingId(null)} title={t('loans.editTitle')} size="xl">
        {editing && <LoanForm key={editing.id} loan={editing} onDone={() => setEditingId(null)} />}
      </Modal>
      <Modal opened={!!deleting} onClose={() => setDeletingId(null)} title={t('loans.deleteTitle')}>
        {deleting && (
          <Stack>
            <Text>{t('loans.deleteQuestion', { name: deleting.name })}</Text>
            <Checkbox label={t('loans.deletePayments')} description={t('loans.deleteKeep')} checked={deletePayments} onChange={(e) => setDeletePayments(e.currentTarget.checked)} />
            <Group justify="flex-end">
              <Button variant="default" onClick={() => setDeletingId(null)}>{t('common.cancel')}</Button>
              <Button color="red" loading={remove.isPending} onClick={() => remove.mutate(deleting.id)}>{t('common.delete')}</Button>
            </Group>
          </Stack>
        )}
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
