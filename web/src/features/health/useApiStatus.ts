import { useQuery } from '@tanstack/react-query';
import { fetchApiStatus } from './fetchApiStatus.ts';

export function useApiStatus() {
  return useQuery({
    queryKey: ['api-status'],
    queryFn: ({ signal }) => fetchApiStatus(signal),
  });
}
