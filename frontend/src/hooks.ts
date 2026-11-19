import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from './api';
import type { Account, Category, Me } from './types';

export const useMe = () =>
  useQuery({
    queryKey: ['me'],
    queryFn: async (): Promise<Me | null> => {
      try {
        return await api<Me>('/me');
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) return null;
        throw e;
      }
    },
  });

export const useCategories = () => useQuery({ queryKey: ['categories'], queryFn: () => api<Category[]>('/categories') });
export const useAccounts = () => useQuery({ queryKey: ['accounts'], queryFn: () => api<Account[]>('/accounts') });

export const canWrite = (role?: string | null) => role !== 'VIEWER';
export const isAdmin = (role?: string | null) => role === 'OWNER' || role === 'ADMIN';

/** Mutation that reports API errors and invalidates the given query keys on success. */
export function useApiMutation<V>(fn: (v: V) => Promise<unknown>, invalidate: string[], onSuccess?: () => void) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: async () => {
      await Promise.all(invalidate.map((k) => qc.invalidateQueries({ queryKey: [k] })));
      onSuccess?.();
    },
    onError: (e: Error) => notifications.show({ color: 'red', message: e.message }),
  });
}
