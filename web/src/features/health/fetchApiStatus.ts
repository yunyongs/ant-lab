import { apiClient } from '../../api/client.ts';
import { ProblemError } from '../../api/problem.ts';

export type ApiStatus =
  | { kind: 'connected'; assetCount: number }
  | { kind: 'apiError'; code: string }
  | { kind: 'unreachable' };

export async function fetchApiStatus(signal?: AbortSignal): Promise<ApiStatus> {
  try {
    const { data } = await apiClient.GET('/api/v1/pool', { signal });
    // Call sites still guard the shape: there is no runtime schema validation.
    if (!Array.isArray(data?.assets)) return { kind: 'unreachable' };
    return { kind: 'connected', assetCount: data.assets.length };
  } catch (error) {
    if (signal?.aborted) throw new Error('aborted', { cause: error });
    if (error instanceof ProblemError) {
      return { kind: 'apiError', code: error.code };
    }
    return { kind: 'unreachable' };
  }
}
