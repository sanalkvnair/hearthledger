import { Button, Center, Stack, Title } from '@mantine/core';
import { IconBrandGoogle } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';

export function LoginPage() {
  const { t } = useTranslation();
  return (
    <Center h="100vh">
      <Stack align="center" gap="lg">
        <Title>{t('appName')}</Title>
        <Title order={4} fw={400}>{t('login.title')}</Title>
        <Button component="a" href="/oauth2/authorization/google" leftSection={<IconBrandGoogle size={18} />} size="md">
          {t('login.google')}
        </Button>
      </Stack>
    </Center>
  );
}
