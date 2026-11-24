import { Anchor, Group, Text, Tooltip } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { useBackendVersion } from '../hooks';

const REPO = 'https://github.com/sanalkvnair/hearthledger';

export function VersionInfo({ align = 'center' }: { align?: 'center' | 'flex-end' }) {
  const { t } = useTranslation();
  const { data: backend } = useBackendVersion();
  const commit = __APP_COMMIT__;
  const mismatch = !!backend && (backend.version !== __APP_VERSION__ || (!!commit && !!backend.commit && backend.commit !== commit));
  const detail = backend
    ? `${t('version.frontend')}: ${__APP_VERSION__} ${commit}\n${t('version.backend')}: ${backend.version} ${backend.commit}${mismatch ? `\n${t('version.mismatch')}` : ''}`
    : `${t('version.frontend')}: ${__APP_VERSION__} ${commit}`;
  const label = __APP_VERSION__ === 'dev' ? 'dev' : `v${__APP_VERSION__}`;

  return (
    <Tooltip label={detail} multiline w={260} style={{ whiteSpace: 'pre-line' }}>
      <Group gap={4} justify={align} wrap="nowrap">
        {mismatch && <Text size="xs" c="orange" aria-label={t('version.mismatch')}>●</Text>}
        <Text size="xs" c="dimmed">{label}</Text>
        {commit && (
          <>
            <Text size="xs" c="dimmed">·</Text>
            <Anchor size="xs" c="dimmed" href={`${REPO}/commit/${commit}`} target="_blank" rel="noopener noreferrer">{commit}</Anchor>
          </>
        )}
      </Group>
    </Tooltip>
  );
}
