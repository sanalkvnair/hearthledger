import { Stack, Table, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { api } from '../api';

interface AuditEntry { id: number; userName: string | null; method: string; path: string; entity: string; entityId: number | null; status: number; at: string }

export function AuditPage() {
  const { t, i18n } = useTranslation();
  const { data = [] } = useQuery({ queryKey: ['audit'], queryFn: () => api<AuditEntry[]>('/audit?limit=200') });
  return (
    <Stack>
      <Title order={2}>{t('nav.audit')}</Title>
      <Table.ScrollContainer minWidth={600}>
        <Table striped>
          <Table.Thead><Table.Tr><Table.Th>{t('common.date')}</Table.Th><Table.Th>{t('family.members')}</Table.Th><Table.Th>{t('common.actions')}</Table.Th><Table.Th>{t('common.type')}</Table.Th></Table.Tr></Table.Thead>
          <Table.Tbody>
            {data.map((e) => (
              <Table.Tr key={e.id}>
                <Table.Td>{new Date(e.at).toLocaleString(i18n.language)}</Table.Td>
                <Table.Td>{e.userName}</Table.Td>
                <Table.Td>{e.method} {e.path}</Table.Td>
                <Table.Td>{e.entity}{e.entityId != null && ` #${e.entityId}`}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Table.ScrollContainer>
    </Stack>
  );
}
