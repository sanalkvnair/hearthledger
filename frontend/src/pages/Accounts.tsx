import { ActionIcon, Button, Group, Modal, NumberInput, Select, Stack, Table, TextInput, Title } from '@mantine/core';
import { IconPencil, IconPlus, IconTrash } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api';
import { money } from '../format';
import { canWrite, isAdmin, useAccounts, useApiMutation, useMe } from '../hooks';
import { ACCOUNT_TYPES, type Account, type AccountType } from '../types';

function AccountForm({ account, onDone }: { account?: Account; onDone: () => void }) {
  const { t } = useTranslation();
  const { data: me } = useMe();
  const [name, setName] = useState(account?.name ?? '');
  const [type, setType] = useState<AccountType>(account?.type ?? 'BANK');
  const [currency, setCurrency] = useState(account?.currency ?? me!.family!.baseCurrency);
  const [opening, setOpening] = useState<number | string>(account?.openingBalance ?? 0);
  const [institution, setInstitution] = useState(account?.institution ?? '');
  const save = useApiMutation(
    () => {
      const body = { name, type, currency, openingBalance: Number(opening), institution: institution || null, ownerUserId: account?.ownerUserId ?? null };
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
      <Group justify="flex-end">
        <Button variant="default" onClick={onDone}>{t('common.cancel')}</Button>
        <Button loading={save.isPending} disabled={!name || currency.length !== 3} onClick={() => save.mutate(undefined)}>{t('common.save')}</Button>
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
