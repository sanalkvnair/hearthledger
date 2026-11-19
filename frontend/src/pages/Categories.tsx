import { ActionIcon, Button, Card, Group, Modal, Select, SimpleGrid, Stack, Text, TextInput, Title } from '@mantine/core';
import { IconPlus, IconTrash } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api';
import { isAdmin, useApiMutation, useCategories, useMe } from '../hooks';
import { NECESSITIES, type Category, type Necessity } from '../types';

interface Draft { type: 'INCOME' | 'EXPENSE'; parentId: number | null }

function CategoryForm({ draft, onDone }: { draft: Draft; onDone: () => void }) {
  const { t } = useTranslation();
  const [name, setName] = useState('');
  const [necessity, setNecessity] = useState<Necessity | null>(null);
  const save = useApiMutation(
    () => api('/categories', 'POST', { name, type: draft.type, parentId: draft.parentId, defaultNecessity: necessity }),
    ['categories'],
    onDone,
  );
  return (
    <Stack>
      <TextInput label={t('common.name')} value={name} onChange={(e) => setName(e.currentTarget.value)} data-autofocus />
      {draft.type === 'EXPENSE' && (
        <Select label={t('txn.necessity')} clearable data={NECESSITIES.map((n) => ({ value: n, label: t(`necessity.${n}`) }))} value={necessity} onChange={(v) => setNecessity(v as Necessity | null)} />
      )}
      <Group justify="flex-end">
        <Button variant="default" onClick={onDone}>{t('common.cancel')}</Button>
        <Button loading={save.isPending} disabled={!name} onClick={() => save.mutate(undefined)}>{t('common.save')}</Button>
      </Group>
    </Stack>
  );
}

function Column({ type, categories, admin, onAdd, onDelete }: {
  type: 'INCOME' | 'EXPENSE';
  categories: Category[];
  admin: boolean;
  onAdd: (d: Draft) => void;
  onDelete: (c: Category) => void;
}) {
  const { t } = useTranslation();
  const list = categories.filter((c) => c.type === type);
  return (
    <Card withBorder padding="md">
      <Group justify="space-between" mb="sm">
        <Title order={4}>{t(type === 'INCOME' ? 'categories.income' : 'categories.expense')}</Title>
        {admin && <Button size="xs" leftSection={<IconPlus size={14} />} onClick={() => onAdd({ type, parentId: null })}>{t('categories.newCategory')}</Button>}
      </Group>
      <Stack gap="xs">
        {list.filter((c) => c.parentId === null).map((p) => (
          <div key={p.id}>
            <Group justify="space-between">
              <Text fw={600}>{p.name}</Text>
              {admin && (
                <Group gap={4}>
                  <Button size="compact-xs" variant="subtle" onClick={() => onAdd({ type, parentId: p.id })}>{t('categories.sub')}</Button>
                  <ActionIcon size="sm" variant="subtle" color="red" aria-label={t('common.delete')} onClick={() => onDelete(p)}><IconTrash size={14} /></ActionIcon>
                </Group>
              )}
            </Group>
            {list.filter((c) => c.parentId === p.id).map((c) => (
              <Group key={c.id} justify="space-between" pl="md">
                <Text size="sm">{c.name}</Text>
                {admin && <ActionIcon size="sm" variant="subtle" color="red" aria-label={t('common.delete')} onClick={() => onDelete(c)}><IconTrash size={14} /></ActionIcon>}
              </Group>
            ))}
          </div>
        ))}
      </Stack>
    </Card>
  );
}

export function CategoriesPage() {
  const { t } = useTranslation();
  const { data: me } = useMe();
  const { data: categories = [] } = useCategories();
  const [draft, setDraft] = useState<Draft | null>(null);
  const remove = useApiMutation((id: number) => api(`/categories/${id}`, 'DELETE'), ['categories']);
  const admin = isAdmin(me?.role);
  const onDelete = (c: Category) => confirm(`${t('common.delete')} "${c.name}"?`) && remove.mutate(c.id);

  return (
    <Stack>
      <Title order={2}>{t('categories.title')}</Title>
      <SimpleGrid cols={{ base: 1, md: 2 }}>
        <Column type="EXPENSE" categories={categories} admin={admin} onAdd={setDraft} onDelete={onDelete} />
        <Column type="INCOME" categories={categories} admin={admin} onAdd={setDraft} onDelete={onDelete} />
      </SimpleGrid>
      <Modal opened={draft !== null} onClose={() => setDraft(null)} title={t('categories.newCategory')}>
        {draft && <CategoryForm draft={draft} onDone={() => setDraft(null)} />}
      </Modal>
    </Stack>
  );
}
