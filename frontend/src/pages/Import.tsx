import { ActionIcon, Alert, Button, Card, Checkbox, FileInput, Group, NumberInput, Select, Stack, Switch, Table, Tabs, Text, TextInput, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconTrash } from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, download, upload } from '../api';
import { money } from '../format';
import { canWrite, useAccounts, useApiMutation, useCategories, useMe } from '../hooks';
import type { CategorizationRule, CsvMapping, ImportPreview, ImportRow, ImportTemplate } from '../types';

const DATE_FORMATS = ['yyyy-MM-dd', 'dd/MM/yyyy', 'MM/dd/yyyy', 'dd.MM.yyyy', 'dd-MM-yyyy', 'yyyy/MM/dd'];
const EMPTY: CsvMapping = { dateColumn: '', dateFormat: 'yyyy-MM-dd', descriptionColumn: '', amountColumn: '', decimalSeparator: '.', invertSign: false };

function Wizard() {
  const { t, i18n } = useTranslation();
  const { data: accounts = [] } = useAccounts();
  const { data: categories = [] } = useCategories();
  const { data: templates = [], refetch: refetchTemplates } = useQuery({ queryKey: ['import-templates'], queryFn: () => api<ImportTemplate[]>('/import/templates') });
  const [source, setSource] = useState<string>('csv');
  const [accountId, setAccountId] = useState<string | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [headers, setHeaders] = useState<string[] | null>(null);
  const [delimiter, setDelimiter] = useState('');
  const [mapping, setMapping] = useState<CsvMapping>(EMPTY);
  const [templateName, setTemplateName] = useState('');
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [rows, setRows] = useState<ImportRow[]>([]);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [busy, setBusy] = useState(false);
  const currency = accounts.find((a) => String(a.id) === accountId)?.currency ?? 'EUR';
  const errorMsg = (e: unknown) => notifications.show({ color: 'red', message: e instanceof Error ? e.message : String(e) });

  const reset = () => { setFile(null); setHeaders(null); setPreview(null); setRows([]); setSelected(new Set()); };
  const showPreview = (p: ImportPreview) => {
    setPreview(p); setRows(p.rows);
    setSelected(new Set(p.rows.map((r, i) => (r.duplicate ? -1 : i)).filter((i) => i >= 0)));
  };

  const inspect = async () => {
    if (!file) return;
    setBusy(true);
    try {
      const fd = new FormData(); fd.append('file', file);
      const res = await upload<{ delimiter: string; headers: string[] }>('/import/csv/inspect', fd);
      setHeaders(res.headers); setDelimiter(res.delimiter);
      setMapping((m) => ({ ...m, delimiter: res.delimiter }));
    } catch (e) { errorMsg(e); } finally { setBusy(false); }
  };

  const runPreview = async () => {
    if (!file || !accountId) return;
    setBusy(true);
    try {
      const fd = new FormData(); fd.append('file', file); fd.append('accountId', accountId);
      if (source === 'csv') {
        const clean = { ...mapping, amountColumn: mapping.amountColumn || null, debitColumn: mapping.debitColumn || null, creditColumn: mapping.creditColumn || null, delimiter: delimiter || null };
        fd.append('mapping', JSON.stringify(clean));
        showPreview(await upload<ImportPreview>('/import/csv/preview', fd));
      } else {
        showPreview(await upload<ImportPreview>('/import/pdf/preview', fd));
      }
    } catch (e) { errorMsg(e); } finally { setBusy(false); }
  };

  const commit = useApiMutation(
    () => api<{ imported: number }>('/import/commit', 'POST', { accountId: Number(accountId), rows: rows.filter((_, i) => selected.has(i)) }).then((r) => {
      notifications.show({ color: 'teal', message: `${t('imp.imported')}: ${r.imported}` });
      reset();
    }),
    ['transactions', 'dashboard', 'savings', 'budgets', 'buffer'],
  );

  const saveTemplate = async () => {
    try {
      await api('/import/templates', 'PUT', { name: templateName, mapping: { ...mapping, delimiter: delimiter || null } });
      setTemplateName(''); refetchTemplates();
    } catch (e) { errorMsg(e); }
  };

  const setRow = (i: number, patch: Partial<ImportRow>) => setRows((rs) => rs.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  const catOptions = (type: string) => categories.filter((c) => c.type === type && !c.archived).map((c) => ({ value: String(c.id), label: c.parentId ? `${categories.find((p) => p.id === c.parentId)?.name} › ${c.name}` : c.name }));
  // incoming rows may also be refunds of an expense: choosing an expense category turns them into one
  const rowCatOptions = (r: ImportRow) => (r.type === 'EXPENSE' ? catOptions('EXPENSE') : [
    { group: t('txn.income'), items: catOptions('INCOME') },
    { group: t('txn.refund'), items: catOptions('EXPENSE') },
  ]);
  const setRowCategory = (i: number, v: string | null, r: ImportRow) => {
    if (r.type === 'EXPENSE') return setRow(i, { categoryId: v ? Number(v) : null });
    const isExpenseCat = categories.find((c) => String(c.id) === v)?.type === 'EXPENSE';
    setRow(i, { categoryId: v ? Number(v) : null, type: isExpenseCat ? 'REFUND' : 'INCOME' });
  };
  const col = headers?.map((h) => ({ value: h, label: h })) ?? [];

  if (preview) {
    return (
      <Stack>
        {preview.errors.length > 0 && <Alert color="yellow" title={t('imp.skipped')}>{preview.errors.slice(0, 10).map((e) => <div key={e}>{e}</div>)}</Alert>}
        <Table.ScrollContainer minWidth={800}>
          <Table>
            <Table.Thead><Table.Tr><Table.Th /><Table.Th>{t('common.date')}</Table.Th><Table.Th>{t('common.note')}</Table.Th><Table.Th ta="right">{t('common.amount')}</Table.Th><Table.Th>{t('txn.category')}</Table.Th></Table.Tr></Table.Thead>
            <Table.Tbody>
              {rows.map((r, i) => (
                <Table.Tr key={i} bg={r.duplicate ? 'var(--mantine-color-yellow-light)' : undefined}>
                  <Table.Td><Checkbox checked={selected.has(i)} onChange={(e) => setSelected((s) => { const n = new Set(s); if (e.currentTarget.checked) n.add(i); else n.delete(i); return n; })} /></Table.Td>
                  <Table.Td>{r.date}</Table.Td>
                  <Table.Td>{r.description}{r.duplicate && <Text size="xs" c="orange">{t('imp.duplicate')}</Text>}</Table.Td>
                  <Table.Td ta="right"><Text span c={r.type === 'EXPENSE' ? 'red' : 'teal'}>{r.type === 'EXPENSE' ? '-' : '+'}{money(r.amount, currency, i18n.language)}</Text>{r.type === 'REFUND' && <Text size="xs" c="lime.8">{t('txn.refund')}</Text>}</Table.Td>
                  <Table.Td w={240}><Select size="xs" clearable searchable data={rowCatOptions(r)} value={r.categoryId?.toString() ?? null} onChange={(v) => setRowCategory(i, v, r)} /></Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
        <Group justify="space-between">
          <Button variant="default" onClick={() => setPreview(null)}>{t('imp.back')}</Button>
          <Button loading={commit.isPending} disabled={selected.size === 0} onClick={() => commit.mutate(undefined)}>{t('imp.importBtn')} ({selected.size})</Button>
        </Group>
      </Stack>
    );
  }

  return (
    <Stack>
      <Tabs value={source} onChange={(v) => { setSource(v ?? 'csv'); reset(); }}>
        <Tabs.List><Tabs.Tab value="csv">CSV</Tabs.Tab><Tabs.Tab value="pdf">PDF</Tabs.Tab></Tabs.List>
      </Tabs>
      {source === 'pdf' && <Alert color="blue">{t('imp.pdfHint')}</Alert>}
      <Select label={t('imp.account')} searchable data={accounts.filter((a) => !a.archived).map((a) => ({ value: String(a.id), label: `${a.name} (${a.currency})` }))} value={accountId} onChange={setAccountId} />
      <FileInput label={t('imp.file')} accept={source === 'csv' ? '.csv,.txt,text/csv' : 'application/pdf'} value={file} onChange={(f) => { setFile(f); setHeaders(null); }} />
      {source === 'csv' && file && !headers && <Button onClick={inspect} loading={busy}>{t('imp.next')}</Button>}
      {source === 'csv' && headers && (
        <Card withBorder>
          <Stack>
            {templates.length > 0 && (
              <Select label={t('imp.template')} clearable data={templates.map((x) => ({ value: String(x.id), label: x.name }))} onChange={(v) => { const tpl = templates.find((x) => String(x.id) === v); if (tpl) { setMapping({ ...EMPTY, ...tpl.mapping }); setDelimiter(tpl.mapping.delimiter ?? delimiter); } }} />
            )}
            <Group grow>
              <TextInput label={t('imp.delimiter')} value={delimiter} onChange={(e) => setDelimiter(e.currentTarget.value)} maxLength={1} />
              <Select label={t('imp.dateFormat')} data={DATE_FORMATS} value={mapping.dateFormat} onChange={(v) => setMapping({ ...mapping, dateFormat: v ?? 'yyyy-MM-dd' })} allowDeselect={false} />
              <Select label={t('imp.decimal')} data={['.', ',']} value={mapping.decimalSeparator} onChange={(v) => setMapping({ ...mapping, decimalSeparator: v ?? '.' })} allowDeselect={false} />
            </Group>
            <Group grow>
              <Select label={t('imp.dateCol')} data={col} value={mapping.dateColumn || null} onChange={(v) => setMapping({ ...mapping, dateColumn: v ?? '' })} />
              <Select label={t('imp.descCol')} data={col} value={mapping.descriptionColumn || null} onChange={(v) => setMapping({ ...mapping, descriptionColumn: v ?? '' })} />
            </Group>
            <Group grow>
              <Select label={t('imp.amountCol')} clearable data={col} value={mapping.amountColumn || null} onChange={(v) => setMapping({ ...mapping, amountColumn: v })} />
              <Select label={t('imp.debitCol')} clearable data={col} value={mapping.debitColumn || null} onChange={(v) => setMapping({ ...mapping, debitColumn: v })} />
              <Select label={t('imp.creditCol')} clearable data={col} value={mapping.creditColumn || null} onChange={(v) => setMapping({ ...mapping, creditColumn: v })} />
            </Group>
            <Switch label={t('imp.invert')} checked={mapping.invertSign} onChange={(e) => setMapping({ ...mapping, invertSign: e.currentTarget.checked })} />
            <Group align="flex-end">
              <TextInput label={t('imp.saveTemplate')} value={templateName} onChange={(e) => setTemplateName(e.currentTarget.value)} />
              <Button variant="default" disabled={!templateName || !mapping.dateColumn} onClick={saveTemplate}>{t('common.save')}</Button>
            </Group>
          </Stack>
        </Card>
      )}
      {(source === 'pdf' ? !!file : !!headers) && (
        <Group justify="flex-end">
          <Button loading={busy} disabled={!accountId || (source === 'csv' && (!mapping.dateColumn || !mapping.descriptionColumn || !(mapping.amountColumn || mapping.debitColumn || mapping.creditColumn)))} onClick={runPreview}>{t('imp.preview')}</Button>
        </Group>
      )}
    </Stack>
  );
}

function Rules() {
  const { t } = useTranslation();
  const { data: categories = [] } = useCategories();
  const { data: rules = [] } = useQuery({ queryKey: ['rules'], queryFn: () => api<CategorizationRule[]>('/import/rules') });
  const [keyword, setKeyword] = useState('');
  const [categoryId, setCategoryId] = useState<string | null>(null);
  const [priority, setPriority] = useState<number | string>(0);
  const add = useApiMutation(() => api('/import/rules', 'POST', { keyword, categoryId: Number(categoryId), priority: Number(priority) }), ['rules'], () => setKeyword(''));
  const remove = useApiMutation((id: number) => api(`/import/rules/${id}`, 'DELETE'), ['rules']);
  return (
    <Stack>
      <Title order={4}>{t('imp.rules')}</Title>
      <Group align="flex-end">
        <TextInput label={t('imp.keyword')} value={keyword} onChange={(e) => setKeyword(e.currentTarget.value)} />
        <Select label={t('txn.category')} searchable data={categories.filter((c) => !c.archived).map((c) => ({ value: String(c.id), label: `${c.name} (${t(`txn.${c.type.toLowerCase()}`)})` }))} value={categoryId} onChange={setCategoryId} />
        <NumberInput label="Priority" w={90} value={priority} onChange={setPriority} />
        <Button disabled={!keyword || !categoryId} onClick={() => add.mutate(undefined)}>{t('common.add')}</Button>
      </Group>
      <Table>
        <Table.Tbody>
          {rules.map((r) => (
            <Table.Tr key={r.id}>
              <Table.Td>{r.keyword}</Table.Td><Table.Td>{r.categoryName}</Table.Td><Table.Td>{r.priority}</Table.Td>
              <Table.Td><ActionIcon variant="subtle" color="red" aria-label={t('common.delete')} onClick={() => remove.mutate(r.id)}><IconTrash size={14} /></ActionIcon></Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Stack>
  );
}

function Export() {
  const { t } = useTranslation();
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const qs = `?${from ? `from=${from}&` : ''}${to ? `to=${to}` : ''}`;
  return (
    <Stack>
      <Title order={4}>{t('imp.exportTitle')}</Title>
      <Group align="flex-end">
        <TextInput type="date" label={t('imp.from')} value={from} onChange={(e) => setFrom(e.currentTarget.value)} />
        <TextInput type="date" label={t('imp.to')} value={to} onChange={(e) => setTo(e.currentTarget.value)} />
        <Button variant="default" onClick={() => download(`/export/transactions.csv${qs}`, 'transactions.csv')}>{t('imp.exportCsv')}</Button>
        <Button variant="default" onClick={() => download(`/export/transactions.xlsx${qs}`, 'transactions.xlsx')}>{t('imp.exportXlsx')}</Button>
      </Group>
    </Stack>
  );
}

export function ImportPage() {
  const { t } = useTranslation();
  const { data: me } = useMe();
  return (
    <Stack gap="xl">
      <Title order={2}>{t('imp.title')}</Title>
      {canWrite(me?.role) && <><Card withBorder><Wizard /></Card><Card withBorder><Rules /></Card></>}
      <Card withBorder><Export /></Card>
    </Stack>
  );
}
