export type Role = 'OWNER' | 'ADMIN' | 'MEMBER' | 'VIEWER';
export type TxnType = 'INCOME' | 'EXPENSE' | 'TRANSFER';
export type Necessity = 'ESSENTIAL' | 'IMPORTANT' | 'DISCRETIONARY';
export type Commitment = 'ONE_TIME' | 'RECURRING' | 'SHORT_TERM_LOAN' | 'LONG_TERM_LOAN';
export type AccountType = 'BANK' | 'CASH' | 'CREDIT_CARD' | 'SAVINGS' | 'FIXED_DEPOSIT' | 'INVESTMENT' | 'LOAN';

export const SAVINGS_TYPES: AccountType[] = ['SAVINGS', 'FIXED_DEPOSIT', 'INVESTMENT'];
export const ROLES: Role[] = ['OWNER', 'ADMIN', 'MEMBER', 'VIEWER'];
export const NECESSITIES: Necessity[] = ['ESSENTIAL', 'IMPORTANT', 'DISCRETIONARY'];
export const COMMITMENTS: Commitment[] = ['ONE_TIME', 'RECURRING', 'SHORT_TERM_LOAN', 'LONG_TERM_LOAN'];
export const ACCOUNT_TYPES: AccountType[] = ['BANK', 'CASH', 'CREDIT_CARD', 'SAVINGS', 'FIXED_DEPOSIT', 'INVESTMENT', 'LOAN'];

export interface Family {
  id: number;
  name: string;
  baseCurrency: string;
  cycleStartDay: number;
  bufferMonths: number;
  bufferLookbackMonths: number;
  llmImportEnabled: boolean;
}

export interface Me {
  userId: number;
  email: string;
  name: string;
  avatarUrl: string | null;
  locale: string;
  theme: 'light' | 'dark' | 'auto';
  role: Role | null;
  family: Family | null;
}

export interface Member {
  userId: number;
  name: string;
  email: string;
  avatarUrl: string | null;
  role: Role;
}

export interface Invitation {
  code: string;
  email: string | null;
  role: Role;
  expiresAt: string;
}

export interface Category {
  id: number;
  parentId: number | null;
  type: 'INCOME' | 'EXPENSE';
  name: string;
  defaultNecessity: Necessity | null;
  archived: boolean;
}

export interface Account {
  id: number;
  ownerUserId: number | null;
  name: string;
  type: AccountType;
  currency: string;
  openingBalance: number;
  institution: string | null;
  archived: boolean;
}

export interface Txn {
  id: number;
  userId: number;
  userName: string;
  type: TxnType;
  amount: number;
  currency: string;
  fxRate: number;
  baseAmount: number;
  date: string;
  accountId: number | null;
  accountName: string | null;
  toAccountId: number | null;
  toAccountName: string | null;
  categoryId: number | null;
  categoryName: string | null;
  commitment: Commitment | null;
  necessity: Necessity | null;
  note: string | null;
  loanId: number | null;
  recurringRuleId: number | null;
}

export interface TxnPage {
  items: Txn[];
  total: number;
  page: number;
  size: number;
}

export interface ExchangeRate {
  id: number;
  fromCurrency: string;
  toCurrency: string;
  rate: number;
  effectiveDate: string;
}

export interface Period {
  start: string;
  end: string;
}

export interface CategoryTotal {
  categoryId: number | null;
  name: string;
  amount: number;
}

export interface Dashboard {
  period: Period;
  baseCurrency: string;
  income: number;
  expense: number;
  savings: number;
  balanceWithoutPrevious: number;
  openingBalance: { computed: number; adjusted: number | null; effective: number };
  balanceWithPrevious: number;
  expenseByCategory: CategoryTotal[];
  incomeByCategory: CategoryTotal[];
  history: { start: string; end: string; income: number; expense: number; savings: number }[];
}

export type Frequency = 'WEEKLY' | 'MONTHLY' | 'QUARTERLY' | 'YEARLY';
export const FREQUENCIES: Frequency[] = ['WEEKLY', 'MONTHLY', 'QUARTERLY', 'YEARLY'];

export interface RecurringRule {
  id: number;
  type: TxnType;
  amount: number;
  currency: string;
  accountId: number | null;
  categoryId: number | null;
  commitment: Commitment | null;
  necessity: Necessity | null;
  note: string | null;
  frequency: Frequency;
  startDate: string;
  endDate: string | null;
  active: boolean;
}

export interface Loan {
  id: number;
  name: string;
  lender: string | null;
  currency: string;
  principal: number;
  interestRate: number;
  tenureMonths: number;
  emi: number;
  startDate: string;
  endDate: string;
  term: 'SHORT_TERM' | 'LONG_TERM';
  ownerUserId: number | null;
  paidInstalments: number;
  remainingInstalments: number;
  remainingBalance: number;
  active: boolean;
}

export interface ScheduleRow {
  number: number;
  date: string;
  payment: number;
  interest: number;
  principal: number;
  balance: number;
}

export interface SavingsAccount {
  accountId: number;
  name: string;
  type: AccountType;
  currency: string;
  institution: string | null;
  ownerUserId: number | null;
  ownerName: string | null;
  contributed: number;
  currentValue: number;
  gain: number;
  interestRate: number | null;
  startDate: string | null;
  maturityDate: string | null;
  maturityAmount: number | null;
  valuedAt: string | null;
  manualValue: number | null;
}

export interface SavingsOverview {
  baseCurrency: string;
  accounts: SavingsAccount[];
  byMember: { userId: number | null; name: string | null; contributed: number; currentValue: number }[];
  totalContributed: number;
  totalValue: number;
}

export interface BudgetProgress {
  id: number;
  categoryId: number;
  categoryName: string;
  amount: number;
  spent: number;
  remaining: number;
  percent: number;
  status: 'OK' | 'WARNING' | 'EXCEEDED';
}

export interface Buffer {
  baseCurrency: string;
  userId: number | null;
  recurringMonthly: number;
  loanMonthly: number;
  essentialMonthlyAverage: number;
  monthlyNeed: number;
  configuredMonths: number;
  requirements: { months: number; amount: number }[];
  liquidSavings: number;
  coverageMonths: number | null;
  shortfall: number;
}

export interface CsvMapping {
  delimiter?: string | null;
  dateColumn: string;
  dateFormat: string;
  descriptionColumn: string;
  amountColumn?: string | null;
  debitColumn?: string | null;
  creditColumn?: string | null;
  decimalSeparator: string;
  invertSign: boolean;
}

export interface ImportRow {
  date: string;
  description: string;
  amount: number;
  type: 'INCOME' | 'EXPENSE';
  categoryId: number | null;
  necessity: Necessity | null;
  commitment: Commitment | null;
  duplicate: boolean;
  error?: string | null;
}

export interface ImportPreview {
  rows: ImportRow[];
  errors: string[];
}

export interface ImportTemplate {
  id: number;
  name: string;
  mapping: CsvMapping;
}

export interface CategorizationRule {
  id: number;
  keyword: string;
  categoryId: number;
  categoryName: string;
  priority: number;
}
