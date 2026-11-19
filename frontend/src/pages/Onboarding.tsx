import { Button, Card, Center, NumberInput, SimpleGrid, Stack, TextInput, Title } from '@mantine/core';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api';
import { useApiMutation } from '../hooks';

export function OnboardingPage({ initialCode }: { initialCode?: string }) {
  const { t } = useTranslation();
  const [name, setName] = useState('');
  const [currency, setCurrency] = useState('EUR');
  const [day, setDay] = useState<number | string>(1);
  const [code, setCode] = useState(initialCode ?? '');

  const done = () => {
    window.history.replaceState(null, '', '/');
  };
  const create = useApiMutation(
    () => api('/families', 'POST', { name, baseCurrency: currency, cycleStartDay: Number(day) }),
    ['me'],
    done,
  );
  const join = useApiMutation(() => api(`/invitations/${encodeURIComponent(code.trim())}/accept`, 'POST'), ['me'], done);

  return (
    <Center mih="100vh" p="md">
      <Stack w="100%" maw={900}>
        <Title ta="center">{t('onboarding.title')}</Title>
        <SimpleGrid cols={{ base: 1, sm: 2 }}>
          <Card withBorder padding="lg">
            <Stack>
              <Title order={4}>{t('onboarding.create')}</Title>
              <TextInput label={t('onboarding.familyName')} value={name} onChange={(e) => setName(e.currentTarget.value)} />
              <TextInput label={t('onboarding.baseCurrency')} maxLength={3} value={currency} onChange={(e) => setCurrency(e.currentTarget.value.toUpperCase())} />
              <NumberInput label={t('onboarding.cycleStartDay')} min={1} max={31} value={day} onChange={setDay} description={t('family.cycleHint')} />
              <Button loading={create.isPending} disabled={!name || currency.length !== 3} onClick={() => create.mutate(undefined)}>
                {t('onboarding.create')}
              </Button>
            </Stack>
          </Card>
          <Card withBorder padding="lg">
            <Stack>
              <Title order={4}>{t('onboarding.join')}</Title>
              <TextInput label={t('onboarding.code')} value={code} onChange={(e) => setCode(e.currentTarget.value)} />
              <Button loading={join.isPending} disabled={!code.trim()} onClick={() => join.mutate(undefined)}>
                {t('onboarding.joinBtn')}
              </Button>
            </Stack>
          </Card>
        </SimpleGrid>
      </Stack>
    </Center>
  );
}
