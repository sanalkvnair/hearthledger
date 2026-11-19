export const money = (value: number, currency: string, locale = 'en') =>
  new Intl.NumberFormat(locale, { style: 'currency', currency }).format(value);

export const isoDate = (d: Date) => {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
};

export const parseIso = (s: string) => {
  const [y, m, d] = s.split('-').map(Number);
  return new Date(y, m - 1, d);
};

export const addDays = (s: string, days: number) => {
  const d = parseIso(s);
  d.setDate(d.getDate() + days);
  return isoDate(d);
};

export const shortDate = (s: string, locale = 'en') =>
  parseIso(s).toLocaleDateString(locale, { day: 'numeric', month: 'short' });
