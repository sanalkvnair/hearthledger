import { ActionIcon, Badge, Button, Group, Modal, MultiSelect, NumberInput, Select, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { IconPencil, IconPlus, IconTrash } from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api';
import { money } from '../format';
import { canWrite, isAdmin, useAccounts, useApiMutation, useMe } from '../hooks';
import { ACCOUNT_TYPES, OWNERSHIPS, type Account, type AccountType, type Member, type Ownership } from '../types';

function AccountForm({ account, onDone }: { account?: Account; onDone: () => void }) {
  const { t } = useTranslation();
  const { data: me } = useMe();
  const [name, setName] = useState(account?.name ?? '');
  const [type, setType] = useState<AccountType>(account?.type ?? 'BANK');
  const [currency, setCurrency] = useState(account?.currency ?? me!.family!.baseCurrency);
  const [opening, setOpening] = useState<number | string>(account?.openingBalance ?? 0);
  const [institution, setInstitution] = useState(account?.institution ?? '');
  const [ownership, setOwnership] = useState<Ownership>(account?.ownership ?? 'FAMILY');
  const [ownerIds, setOwnerIds] = useState<string[]>(account?.ownerIds.map(String) ?? (me?.userId ? [String(me.userId)] : []));
  const [statementDay, setStatementDay] = useState<number | string>(account?.statementDay ?? '');
  const [dueDay, setDueDay] = useState<number | string>(account?.dueDay ?? '');
  const { data: members = [] } = useQuery({ queryKey: ['members'], queryFn: () => api<Member[]>('/family/members') });
  const isCard = type === 'CREDIT_CARD';
  const save = useApiMutation(
    () => {
      const body = { name, type, currency, openingBalance: Number(opening), institution: institution || null,
        ownership, ownerIds: ownership === 'FAMILY' ? [] : ownerIds.map(Number),
        statementDay: isCard && statementDay !== '' ? Number(statementDay) : null, dueDay: isCard && dueDay !== '' ? Number(dueDay) : null,
      };
      return account ? api(`/accounts/${account.id}`, 'PUT', body) : api('/accounts', 'POST', body);
    },
    ['accounts'],
    onDone,
  );
  return (
    <Stack>
      <TextInput label={t('common.name')} value={name} onChange={(e) => setName(e.currentTarget.value)} data-autofocus />
      <Select label={t('common.type')} data={ACCOUNT_TYPES.map((a) => ({ value: a, label: t(`accountType.${a}`) }))} value={type} onChange={(v) => setType(v as AccountType)} allowDeselect={false} />
      <Group grow>
        <TextInput label={t('common.currency')} maxLength={3} value={currency} onChange={(e) => setCurrency(e.currentTarget.value.toUpperCase())} />
        <NumberInput label={t('accounts.openingBalance')} value={opening} onChange={setOpening} decimalScale={2} />
      </Group>
      <TextInput label={t('accounts.institution')} value={institution} onChange={(e) => setInstitution(e.currentTarget.value)} />
      <Select label={t('p5.ownership')} data={OWNERSHIPS.map((o) => ({ value: o, label: t(`p5.${o}`) }))} value={ownership} onChange={(v) => setOwnership(v as Ownership)} allowDeselect={false} />
      {ownership !== 'FAMILY' && (
        <MultiSelect
          label={t('p5.owners')}
          data={members.map((m) => ({ value: String(m.userId), label: m.name }))}
          value={ownerIds}
          onChange={(v) => setOwnerIds(ownership === 'PRIVATE' ? v.slice(-1) : v)}
        />
      )}
      {isCard && (
        <>
          <Group grow>
            <NumberInput label={t('p5.statementDay')} min={1} max={31} value={statementDay} onChange={setStatementDay} />
            <NumberInput label={t('p5.dueDay')} min={1} max={31} value={dueDay} onChange={setDueDay} />
          </Group>
          <Text size="xs" c="dimmed">{t('p5.cardHint')}</Text>
        </>
      )}
      <Group justify="flex-end">
        <Button variant="default" onClick={onDone}>{t('common.cancel')}</Button>
        <Button loading={save.isPending} disabled={!name || currency.length !== 3 || (ownership !== 'FAMILY' && ownerIds.length === 0) || (ownership === 'JOINT' && ownerIds.length < 2) || (isCard && (statementDay === '') !== (dueDay === ''))} onClick={() => save.mutate(undefined)}>{t('common.save')}</Button>
      </Group>
    </Stack>
  );
}

export function AccountsPage() {
  const { t, i18n } = useTranslation();
  const { data: me } = useMe();
  const { data: accounts = [] } = useAccounts();
  const [editing, setEditing] = useState<Account | 'new' | null>(null);
  const remove = useApiMutation((id: number) => api(`/accounts/${id}`, 'DELETE'), ['accounts']);

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={2}>{t('accounts.title')}</Title>
        {canWrite(me?.role) && <Button leftSection={<IconPlus size={16} />} onClick={() => setEditing('new')}>{t('common.add')}</Button>}
      </Group>
      <Table striped>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{t('common.name')}</Table.Th>
            <Table.Th>{t('common.type')}</Table.Th>
            <Table.Th>{t('accounts.institution')}</Table.Th>
            <Table.Th>{t('p5.ownership')}</Table.Th>
            <Table.Th ta="right">{t('accounts.openingBalance')}</Table.Th>
            <Table.Th />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {accounts.map((a) => (
            <Table.Tr key={a.id}>
              <Table.Td>{a.name}</Table.Td>
              <Table.Td>{t(`accountType.${a.type}`)}</Table.Td>
              <Table.Td>{a.institution}</Table.Td>
              <Table.Td>
                <Badge variant="light" color={a.ownership === 'PRIVATE' ? 'gray' : a.ownership === 'JOINT' ? 'grape' : 'blue'}>{t(`p5.${a.ownership}`)}</Badge>
                {a.statementDay != null && <Text size="xs" c="dimmed">{t('p5.statementDay')} {a.statementDay} · {t('p5.dueDay')} {a.dueDay}</Text>}
              </Table.Td>
              <Table.Td ta="right">{money(a.openingBalance, a.currency, i18n.language)}</Table.Td>
              <Table.Td>
                {canWrite(me?.role) && (
                  <Group gap={4} wrap="nowrap">
                    <ActionIcon variant="subtle" aria-label={t('common.edit')} onClick={() => setEditing(a)}><IconPencil size={16} /></ActionIcon>
                    {isAdmin(me?.role) && (
                      <ActionIcon variant="subtle" color="red" aria-label={t('common.delete')} onClick={() => confirm(t('common.delete') + '?') && remove.mutate(a.id)}><IconTrash size={16} /></ActionIcon>
                    )}
                  </Group>
                )}
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
      <Modal opened={editing !== null} onClose={() => setEditing(null)} title={t('accounts.title')}>
        {editing && <AccountForm account={editing === 'new' ? undefined : editing} onDone={() => setEditing(null)} />}
      </Modal>
    </Stack>
  );
}
