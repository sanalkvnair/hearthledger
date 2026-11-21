import { BarChart, DonutChart } from '@mantine/charts';
import { ActionIcon, Button, Card, Group, Loader, NumberInput, Progress, SegmentedControl, SimpleGrid, Stack, Text, Title } from '@mantine/core';
import { IconChevronLeft, IconChevronRight } from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api';
import { addDays, money, shortDate } from '../format';
import { canWrite, useApiMutation, useMe } from '../hooks';
import { STATUS_COLOR } from './Budgets';
import type { Buffer, BudgetProgress, CardSummary, CategoryTotal, Dashboard, Member } from '../types';

const PALETTE = ['blue.6', 'teal.6', 'orange.6', 'grape.6', 'red.6', 'cyan.6', 'lime.6', 'pink.6', 'yellow.6', 'indigo.6'];

function Stat({ label, value, color }: { label: string; value: string; color?: string }) {
  return (
    <Card withBorder padding="md">
      <Text size="sm" c="dimmed">{label}</Text>
      <Text size="xl" fw={700} c={color}>{value}</Text>
    </Card>
  );
}

function CategoryDonut({ title, data, fmt, empty, action }: { title: string; data: CategoryTotal[]; fmt: (n: number) => string; empty: string; action?: ReactNode }) {
  // categories with more refunds than spending net below zero and cannot be drawn
  data = data.filter((c) => c.amount > 0);
  return (
    <Card withBorder padding="md">
      <Group justify="space-between" mb="sm"><Title order={5}>{title}</Title>{action}</Group>
      {data.length === 0 ? (
        <Text c="dimmed">{empty}</Text>
      ) : (
        <Group justify="center">
          <DonutChart
            data={data.map((c, i) => ({ name: c.name, value: c.amount, color: PALETTE[i % PALETTE.length] }))}
            valueFormatter={fmt}
            withLabelsLine={false}
            size={200}
            thickness={28}
          />
        </Group>
      )}
    </Card>
  );
}

function BufferCard() {
  const { t, i18n } = useTranslation();
  const [scope, setScope] = useState('family');
  const { data: members = [] } = useQuery({ queryKey: ['members'], queryFn: () => api<Member[]>('/family/members') });
  const { data } = useQuery({
    queryKey: ['buffer', scope],
    queryFn: () => api<Buffer>(scope === 'family' ? '/buffer' : `/buffer?userId=${scope}`),
  });
  if (!data) return null;
  const fmt = (n: number) => money(n, data.baseCurrency, i18n.language);
  return (
    <Card withBorder padding="md">
      <Group justify="space-between" mb="sm">
        <Title order={5}>{t('buffer.title')}</Title>
        <SegmentedControl size="xs" value={scope} onChange={setScope}
          data={[{ value: 'family', label: t('buffer.family') }, ...members.map((m) => ({ value: String(m.userId), label: m.name }))]} />
      </Group>
      <SimpleGrid cols={{ base: 1, sm: 3 }} mb="sm">
        {data.requirements.map((r) => (
          <div key={r.months}>
            <Text size="sm" c="dimmed">{r.months} {t('buffer.months')}</Text>
            <Text size="xl" fw={700}>{fmt(r.amount)}</Text>
          </div>
        ))}
      </SimpleGrid>
      <Text size="sm" c="dimmed">
        {t('buffer.need')}: {fmt(data.monthlyNeed)} = {t('buffer.recurring')} {fmt(data.recurringMonthly)} + {t('buffer.emi')} {fmt(data.loanMonthly)} + {t('buffer.essential')} {fmt(data.essentialMonthlyAverage)}
      </Text>
      <Text size="sm" mt={4}>
        {t('buffer.savingsHeld')}: {fmt(data.liquidSavings)}
        {data.cardBillsDue > 0 && <Text span c="orange"> ({t('p5.cardsDue')}: {fmt(data.cardBillsDue)})</Text>}
        {data.coverageMonths != null && ` · ${t('buffer.coverage')} ${data.coverageMonths} ${t('buffer.months')}`}
        {data.shortfall > 0 && <Text span c="red"> · {t('buffer.shortfall')}: {fmt(data.shortfall)}</Text>}
      </Text>
    </Card>
  );
}

function CardBills() {
  const { t, i18n } = useTranslation();
  const { data = [] } = useQuery({ queryKey: ['card-bills'], queryFn: () => api<CardSummary[]>('/dashboard/card-bills') });
  if (data.length === 0) return null;
  return (
    <Card withBorder padding="md">
      <Title order={5} mb="sm">{t('p5.cardBills')}</Title>
      <Stack gap="xs">
        {data.map((c) => {
          const fmt = (n: number) => money(n, c.currency, i18n.language);
          return (
            <Group key={c.accountId} justify="space-between" wrap="nowrap">
              <div>
                <Text fw={500}>{c.name}</Text>
                <Text size="xs" c="dimmed">
                  {t('p5.statement')} {fmt(c.statementAmount)} · {t('p5.due')} {shortDate(c.dueDate, i18n.language)} · {t('p5.open')}: {fmt(c.openAmount)}
                </Text>
              </div>
              <Text fw={700} c={c.overdue ? 'red' : c.remaining > 0 ? undefined : 'teal'}>
                {c.overdue && <Text span size="xs" c="red">{t('p5.overdue')} </Text>}{fmt(c.remaining)}
              </Text>
            </Group>
          );
        })}
      </Stack>
    </Card>
  );
}

function BudgetAlerts({ fmt }: { fmt: (n: number) => string }) {
  const { t } = useTranslation();
  const { data = [] } = useQuery({ queryKey: ['budgets'], queryFn: () => api<BudgetProgress[]>('/budgets') });
  const items = data.filter((b) => b.status !== 'OK');
  if (items.length === 0) return null;
  return (
    <Card withBorder padding="md">
      <Title order={5} mb="sm">{t('budgets.title')}</Title>
      <Stack gap="xs">
        {items.map((b) => (
          <div key={b.id}>
            <Group justify="space-between"><Text size="sm">{b.categoryName}</Text><Text size="sm">{fmt(b.spent)} / {fmt(b.amount)}</Text></Group>
            <Progress value={Math.min(b.percent, 100)} color={STATUS_COLOR[b.status]} />
          </div>
        ))}
      </Stack>
    </Card>
  );
}

export function DashboardPage() {
  const { t, i18n } = useTranslation();
  const { data: me } = useMe();
  const [date, setDate] = useState<string | undefined>();
  const [override, setOverride] = useState<number | string>('');
  const [method, setMethod] = useState('ALL');
  const { data, isLoading } = useQuery({
    queryKey: ['dashboard', date, method],
    queryFn: () => api<Dashboard>(`/dashboard?paymentMethod=${method}${date ? `&date=${date}` : ''}`),
  });
  const setOpening = useApiMutation(
    (amount: number) => api(`/periods/${data!.period.start}/opening-balance`, 'PUT', { amount }),
    ['dashboard'],
  );
  const clearOpening = useApiMutation(() => api(`/periods/${data!.period.start}/opening-balance`, 'DELETE'), ['dashboard']);

  if (isLoading || !data) return <Loader />;
  const fmt = (n: number) => money(n, data.baseCurrency, i18n.language);
  const { period, openingBalance } = data;
  const writer = canWrite(me?.role);

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={2}>{t('nav.dashboard')}</Title>
        <Group gap="xs">
          <ActionIcon variant="default" aria-label={t('common.previous')} onClick={() => setDate(addDays(period.start, -1))}>
            <IconChevronLeft size={16} />
          </ActionIcon>
          <Text fw={500}>{shortDate(period.start, i18n.language)} – {shortDate(period.end, i18n.language)}</Text>
          <ActionIcon variant="default" aria-label={t('common.next')} onClick={() => setDate(addDays(period.end, 1))}>
            <IconChevronRight size={16} />
          </ActionIcon>
          <Button variant="subtle" size="xs" onClick={() => setDate(undefined)}>{t('common.today')}</Button>
        </Group>
      </Group>

      <SimpleGrid cols={{ base: 1, sm: 3 }}>
        <Stat label={t('dashboard.income')} value={fmt(data.income)} color="teal" />
        <Stat label={t('dashboard.expense')} value={fmt(data.expense)} color="red" />
        <Stat label={t('dashboard.savings')} value={fmt(data.savings)} color="blue" />
      </SimpleGrid>

      <SimpleGrid cols={{ base: 1, sm: 3 }}>
        <Stat label={t('dashboard.balanceWithout')} value={fmt(data.balanceWithoutPrevious)} />
        <Card withBorder padding="md">
          <Text size="sm" c="dimmed">{t('dashboard.previousBalance')}</Text>
          <Text size="xl" fw={700}>{fmt(openingBalance.effective)}</Text>
          {openingBalance.adjusted !== null && (
            <Text size="xs" c="dimmed">{t('dashboard.calculated')}: {fmt(openingBalance.computed)}</Text>
          )}
          {writer && (
            <Group gap="xs" mt="xs" wrap="nowrap">
              <NumberInput size="xs" placeholder={t('dashboard.adjustPrevious')} value={override} onChange={setOverride} decimalScale={2} />
              <Button size="xs" disabled={override === ''} onClick={() => setOpening.mutate(Number(override), { onSuccess: () => setOverride('') })}>
                {t('common.save')}
              </Button>
              {openingBalance.adjusted !== null && (
                <Button size="xs" variant="subtle" onClick={() => clearOpening.mutate(undefined)}>{t('dashboard.resetPrevious')}</Button>
              )}
            </Group>
          )}
        </Card>
        <Stat label={t('dashboard.balanceWith')} value={fmt(data.balanceWithPrevious)} color={data.balanceWithPrevious < 0 ? 'red' : 'teal'} />
      </SimpleGrid>

      <Card withBorder padding="md">
        <Title order={5} mb="sm">{t('dashboard.history')}</Title>
        <BarChart
          h={280}
          dataKey="label"
          data={data.history.map((h) => ({ label: shortDate(h.start, i18n.language), ...h }))}
          series={[
            { name: 'income', label: t('dashboard.income'), color: 'teal.6' },
            { name: 'expense', label: t('dashboard.expense'), color: 'red.6' },
            { name: 'savings', label: t('dashboard.savings'), color: 'blue.6' },
          ]}
          valueFormatter={fmt}
          withLegend
        />
      </Card>

      <BufferCard />
      <CardBills />
      <BudgetAlerts fmt={fmt} />

      <SimpleGrid cols={{ base: 1, md: 2 }}>
        <CategoryDonut
          title={t('dashboard.expenseBy')} data={data.expenseByCategory} fmt={fmt} empty={t('dashboard.empty')}
          action={<SegmentedControl size="xs" value={method} onChange={setMethod} data={['ALL', 'CARD', 'BANK', 'CASH'].map((m) => ({ value: m, label: m === 'ALL' ? t('common.all') : t(`p5.${m}`) }))} />}
        />
        <CategoryDonut title={t('dashboard.incomeBy')} data={data.incomeByCategory} fmt={fmt} empty={t('dashboard.empty')} />
      </SimpleGrid>
    </Stack>
  );
}
