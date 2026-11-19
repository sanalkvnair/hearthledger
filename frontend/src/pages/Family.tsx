import { Button, Card, CopyButton, Group, NumberInput, Select, Stack, Switch, Table, Tabs, Text, TextInput, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api';
import { isAdmin, useAccounts, useApiMutation, useMe } from '../hooks';
import { ROLES, type ExchangeRate, type Invitation, type Member, type Role } from '../types';
import i18n from '../i18n';

function Settings() {
  const { t } = useTranslation();
  const { data: me } = useMe();
  const f = me!.family!;
  const admin = isAdmin(me?.role);
  const [name, setName] = useState(f.name);
  const [day, setDay] = useState<number | string>(f.cycleStartDay);
  const [buffer, setBuffer] = useState<number | string>(f.bufferMonths);
  const [lookback, setLookback] = useState<number | string>(f.bufferLookbackMonths);
  const [llm, setLlm] = useState(f.llmImportEnabled);
  const [adjust, setAdjust] = useState(f.adjustPeriodToBusinessDay);
  const [country, setCountry] = useState(f.holidayCountry);
  const save = useApiMutation(
    () => api('/family', 'PUT', { name, cycleStartDay: Number(day), bufferMonths: Number(buffer), bufferLookbackMonths: Number(lookback), llmImportEnabled: llm, adjustPeriodToBusinessDay: adjust, holidayCountry: country }),
    ['me', 'dashboard', 'period', 'transactions'],
  );
  return (
    <Card withBorder maw={500}>
      <Stack>
        <TextInput label={t('common.name')} value={name} onChange={(e) => setName(e.currentTarget.value)} disabled={!admin} />
        <NumberInput label={t('onboarding.cycleStartDay')} description={t('family.cycleHint')} min={1} max={31} value={day} onChange={setDay} disabled={!admin} />
        <Switch label={t('p5.adjustBusiness')} checked={adjust} onChange={(e) => setAdjust(e.currentTarget.checked)} disabled={!admin} />
        {adjust && <TextInput label={t('p5.holidayCountry')} maxLength={2} value={country} onChange={(e) => setCountry(e.currentTarget.value.toUpperCase())} disabled={!admin} />}
        <Group grow>
          <NumberInput label={t('family.bufferMonths')} min={1} max={24} value={buffer} onChange={setBuffer} disabled={!admin} />
          <NumberInput label={t('family.lookback')} min={1} max={24} value={lookback} onChange={setLookback} disabled={!admin} />
        </Group>
        <Switch label="AI-assisted PDF import" checked={llm} onChange={(e) => setLlm(e.currentTarget.checked)} disabled={!admin} />
        {admin && <Button loading={save.isPending} onClick={() => save.mutate(undefined)}>{t('common.save')}</Button>}
      </Stack>
    </Card>
  );
}

function DefaultAccounts() {
  const { t } = useTranslation();
  const { data: me } = useMe();
  const { data: accounts = [] } = useAccounts();
  const [expense, setExpense] = useState<string | null>(me?.defaultExpenseAccountId?.toString() ?? null);
  const [income, setIncome] = useState<string | null>(me?.defaultIncomeAccountId?.toString() ?? null);
  const [savings, setSavings] = useState<string | null>(me?.defaultSavingsSourceAccountId?.toString() ?? null);
  const options = accounts.filter((a) => !a.archived).map((a) => ({ value: String(a.id), label: `${a.name} (${a.currency})` }));
  const num = (v: string | null) => (v ? Number(v) : null);
  const save = useApiMutation(
    () => api('/me/default-accounts', 'PUT', { expenseAccountId: num(expense), incomeAccountId: num(income), savingsSourceAccountId: num(savings) }),
    ['me'],
  );
  return (
    <Card withBorder maw={500}>
      <Stack>
        <Title order={5}>{t('p5.defaults')}</Title>
        <Select label={t('p5.defExpense')} data={options} value={expense} onChange={setExpense} clearable searchable />
        <Select label={t('p5.defIncome')} data={options} value={income} onChange={setIncome} clearable searchable />
        <Select label={t('p5.defSavings')} data={options} value={savings} onChange={setSavings} clearable searchable />
        <Button loading={save.isPending} onClick={() => save.mutate(undefined)}>{t('common.save')}</Button>
      </Stack>
    </Card>
  );
}

function Members() {
  const { t } = useTranslation();
  const { data: me } = useMe();
  const admin = isAdmin(me?.role);
  const { data: members = [] } = useQuery({ queryKey: ['members'], queryFn: () => api<Member[]>('/family/members') });
  const { data: invitations = [] } = useQuery({ queryKey: ['invitations'], enabled: admin, queryFn: () => api<Invitation[]>('/family/invitations') });
  const [inviteRole, setInviteRole] = useState<Role>('MEMBER');
  const changeRole = useApiMutation((v: { id: number; role: Role }) => api(`/family/members/${v.id}/role`, 'PUT', { role: v.role }), ['members']);
  const remove = useApiMutation((id: number) => api(`/family/members/${id}`, 'DELETE'), ['members']);
  const invite = useApiMutation(() => api('/family/invitations', 'POST', { role: inviteRole }), ['invitations']);
  const link = (code: string) => `${window.location.origin}/join/${code}`;

  return (
    <Stack>
      <Table striped maw={700}>
        <Table.Tbody>
          {members.map((m) => (
            <Table.Tr key={m.userId}>
              <Table.Td>{m.name}<Text size="xs" c="dimmed">{m.email}</Text></Table.Td>
              <Table.Td>
                {admin && m.role !== 'OWNER' ? (
                  <Select size="xs" w={130} data={ROLES.filter((r) => r !== 'OWNER').map((r) => ({ value: r, label: t(`roles.${r}`) }))} value={m.role} allowDeselect={false} onChange={(v) => changeRole.mutate({ id: m.userId, role: v as Role })} />
                ) : t(`roles.${m.role}`)}
              </Table.Td>
              <Table.Td>
                {(admin || m.userId === me!.userId) && m.role !== 'OWNER' && (
                  <Button size="compact-xs" color="red" variant="subtle" onClick={() => confirm(t('family.remove') + '?') && remove.mutate(m.userId)}>{t('family.remove')}</Button>
                )}
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
      {admin && (
        <Card withBorder maw={700}>
          <Stack>
            <Title order={5}>{t('family.invitations')}</Title>
            <Group align="flex-end">
              <Select label={t('family.role')} w={150} data={ROLES.filter((r) => r !== 'OWNER').map((r) => ({ value: r, label: t(`roles.${r}`) }))} value={inviteRole} allowDeselect={false} onChange={(v) => setInviteRole(v as Role)} />
              <Button loading={invite.isPending} onClick={() => invite.mutate(undefined)}>{t('family.invite')}</Button>
            </Group>
            {invitations.map((i) => (
              <Group key={i.code} justify="space-between" wrap="nowrap">
                <Text size="sm" truncate>{link(i.code)} ({t(`roles.${i.role}`)})</Text>
                <CopyButton value={link(i.code)}>{({ copied, copy }) => <Button size="compact-xs" onClick={copy}>{copied ? '✓' : t('common.copy')}</Button>}</CopyButton>
              </Group>
            ))}
          </Stack>
        </Card>
      )}
    </Stack>
  );
}

function Rates() {
  const { t } = useTranslation();
  const { data: me } = useMe();
  const base = me!.family!.baseCurrency;
  const { data: rates = [] } = useQuery({ queryKey: ['rates'], queryFn: () => api<ExchangeRate[]>('/exchange-rates') });
  const [from, setFrom] = useState('');
  const [rate, setRate] = useState<number | string>('');
  const [date, setDate] = useState(new Date().toISOString().slice(0, 10));
  const add = useApiMutation(() => api('/exchange-rates', 'POST', { fromCurrency: from, rate: Number(rate), effectiveDate: date }), ['rates'], () => { setFrom(''); setRate(''); });
  const remove = useApiMutation((id: number) => api(`/exchange-rates/${id}`, 'DELETE'), ['rates']);
  return (
    <Stack maw={700}>
      <Group align="flex-end">
        <TextInput label={t('family.rateFrom')} maxLength={3} w={110} value={from} onChange={(e) => setFrom(e.currentTarget.value.toUpperCase())} />
        <NumberInput label={`${t('family.rate')} (${base})`} value={rate} onChange={setRate} min={0} decimalScale={6} />
        <TextInput label={t('family.effective')} type="date" value={date} onChange={(e) => setDate(e.currentTarget.value)} />
        <Button disabled={from.length !== 3 || !rate} loading={add.isPending} onClick={() => add.mutate(undefined)}>{t('common.add')}</Button>
      </Group>
      <Table striped>
        <Table.Tbody>
          {rates.map((r) => (
            <Table.Tr key={r.id}>
              <Table.Td>{r.effectiveDate}</Table.Td>
              <Table.Td>1 {r.fromCurrency} = {r.rate} {r.toCurrency}</Table.Td>
              <Table.Td><Button size="compact-xs" color="red" variant="subtle" onClick={() => remove.mutate(r.id)}>{t('common.delete')}</Button></Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Stack>
  );
}

function Preferences() {
  const { t } = useTranslation();
  const { data: me } = useMe();
  const save = useApiMutation(
    (v: { locale: string; theme: string }) => api('/me/preferences', 'PUT', v),
    ['me'],
    () => localStorage.setItem('locale', i18n.language),
  );
  return (
    <Card withBorder maw={400}>
      <Stack>
        <Select label={t('family.language')} data={[{ value: 'en', label: 'English' }, { value: 'sv', label: 'Svenska' }]} value={me!.locale} allowDeselect={false} onChange={(v) => v && save.mutate({ locale: v, theme: me!.theme })} />
        <Select label={t('family.theme')} data={[{ value: 'auto', label: t('family.auto') }, { value: 'light', label: t('family.light') }, { value: 'dark', label: t('family.dark') }]} value={me!.theme} allowDeselect={false} onChange={(v) => v && save.mutate({ locale: me!.locale, theme: v })} />
      </Stack>
    </Card>
  );
}

export function FamilyPage() {
  const { t } = useTranslation();
  return (
    <Stack>
      <Title order={2}>{t('nav.family')}</Title>
      <Tabs defaultValue="settings">
        <Tabs.List mb="md">
          <Tabs.Tab value="settings">{t('family.settings')}</Tabs.Tab>
          <Tabs.Tab value="members">{t('family.members')}</Tabs.Tab>
          <Tabs.Tab value="rates">{t('family.rates')}</Tabs.Tab>
          <Tabs.Tab value="prefs">{t('family.preferences')}</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="settings"><Settings /></Tabs.Panel>
        <Tabs.Panel value="members"><Members /></Tabs.Panel>
        <Tabs.Panel value="rates"><Rates /></Tabs.Panel>
        <Tabs.Panel value="prefs"><Stack><Preferences /><DefaultAccounts /></Stack></Tabs.Panel>
      </Tabs>
    </Stack>
  );
}
