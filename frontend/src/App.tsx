import { AppShell, Avatar, Button, Center, Group, Loader, NavLink, Stack, Text, Title, useMantineColorScheme } from '@mantine/core';
import { IconBuildingBank, IconCalendarRepeat, IconCategory, IconChartPie, IconFileImport, IconHistory, IconPigMoney, IconReceipt, IconReportMoney, IconTargetArrow, IconUsers } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { api } from './api';
import { isAdmin, useMe } from './hooks';
import { AuditPage } from './pages/Audit';
import { AccountsPage } from './pages/Accounts';
import { BudgetsPage } from './pages/Budgets';
import { CategoriesPage } from './pages/Categories';
import { DashboardPage } from './pages/Dashboard';
import { FamilyPage } from './pages/Family';
import { ImportPage } from './pages/Import';
import { LoansPage } from './pages/Loans';
import { LoginPage } from './pages/Login';
import { OnboardingPage } from './pages/Onboarding';
import { RecurringPage } from './pages/Recurring';
import { SavingsPage } from './pages/Savings';
import { TransactionsPage } from './pages/Transactions';

export function App() {
  const { data: me, isLoading } = useMe();
  const { i18n } = useTranslation();
  const { setColorScheme } = useMantineColorScheme();
  const joinCode = /^\/join\/(.+)$/.exec(useLocation().pathname)?.[1];

  useEffect(() => {
    if (!me) return;
    setColorScheme(me.theme);
    if (me.locale !== i18n.language) {
      localStorage.setItem('locale', me.locale);
      void i18n.changeLanguage(me.locale);
    }
  }, [me, i18n, setColorScheme]);

  if (isLoading) return <Center h="100vh"><Loader /></Center>;
  if (!me) return <LoginPage />;

  if (!me.family) return <OnboardingPage initialCode={joinCode} />;
  return <Shell />;
}

function Shell() {
  const { t } = useTranslation();
  const { data: me } = useMe();
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const qc = useQueryClient();

  const links = [
    { to: '/', label: t('nav.dashboard'), icon: IconChartPie },
    { to: '/transactions', label: t('nav.transactions'), icon: IconReceipt },
    { to: '/recurring', label: t('nav.recurring'), icon: IconCalendarRepeat },
    { to: '/accounts', label: t('nav.accounts'), icon: IconBuildingBank },
    { to: '/savings', label: t('nav.savings'), icon: IconPigMoney },
    { to: '/loans', label: t('nav.loans'), icon: IconReportMoney },
    { to: '/budgets', label: t('nav.budgets'), icon: IconTargetArrow },
    { to: '/import', label: t('nav.import'), icon: IconFileImport },
    { to: '/categories', label: t('nav.categories'), icon: IconCategory },
    { to: '/family', label: t('nav.family'), icon: IconUsers },
    ...(isAdmin(me?.role) ? [{ to: '/audit', label: t('nav.audit'), icon: IconHistory }] : []),
  ];

  const logout = async () => {
    await api('/auth/logout', 'POST');
    qc.clear();
    window.location.href = '/';
  };

  return (
    <AppShell navbar={{ width: 240, breakpoint: 'sm' }} padding="md">
      <AppShell.Navbar p="md">
        <Stack justify="space-between" h="100%">
          <Stack gap="xs">
            <Title order={4} mb="sm">{t('appName')}</Title>
            {links.map((l) => (
              <NavLink
                key={l.to}
                label={l.label}
                leftSection={<l.icon size={18} />}
                active={l.to === '/' ? pathname === '/' : pathname.startsWith(l.to)}
                onClick={() => navigate(l.to)}
              />
            ))}
          </Stack>
          <Stack gap="xs">
            <Group gap="xs" wrap="nowrap">
              <Avatar src={me?.avatarUrl} size="sm" radius="xl" />
              <Text size="sm" truncate>{me?.name}</Text>
            </Group>
            <Button variant="subtle" size="xs" onClick={logout}>{t('common.logout')}</Button>
          </Stack>
        </Stack>
      </AppShell.Navbar>
      <AppShell.Main>
        <Routes>
          <Route path="/" element={<DashboardPage />} />
          <Route path="/transactions" element={<TransactionsPage />} />
          <Route path="/recurring" element={<RecurringPage />} />
          <Route path="/savings" element={<SavingsPage />} />
          <Route path="/loans" element={<LoansPage />} />
          <Route path="/budgets" element={<BudgetsPage />} />
          <Route path="/import" element={<ImportPage />} />
          <Route path="/accounts" element={<AccountsPage />} />
          <Route path="/categories" element={<CategoriesPage />} />
          <Route path="/audit" element={<AuditPage />} />
          <Route path="/family" element={<FamilyPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </AppShell.Main>
    </AppShell>
  );
}
