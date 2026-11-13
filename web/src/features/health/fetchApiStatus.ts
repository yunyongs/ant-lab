export type ApiStatus =
  | { kind: 'connected'; assetCount: number }
  | { kind: 'apiError'; code: string }
  | { kind: 'unreachable' };

interface PoolBody {
  assets: unknown[];
}

interface ProblemBody {
  code?: unknown;
}

// Classified by content type, not status: a dev proxy answers 5xx with
// HTML or text when the API is down, which is not an API answer.
export async function fetchApiStatus(signal?: AbortSignal): Promise<ApiStatus> {
  let response: Response;
  try {
    const url = new URL('/api/v1/pool', window.location.origin);
    response = await fetch(url, { signal });
  } catch {
    if (signal?.aborted) throw new Error('aborted');
    return { kind: 'unreachable' };
  }
  const type = response.headers.get('content-type') ?? '';
  try {
    if (type.includes('application/problem+json')) {
      const body = (await response.json()) as ProblemBody;
      return {
        kind: 'apiError',
        code: typeof body.code === 'string' ? body.code : 'UNKNOWN',
      };
    }
    if (response.ok && type.includes('application/json')) {
      const body = (await response.json()) as Partial<PoolBody>;
      if (!Array.isArray(body.assets)) return { kind: 'unreachable' };
      return { kind: 'connected', assetCount: body.assets.length };
    }
  } catch {
    return { kind: 'unreachable' };
  }
  return { kind: 'unreachable' };
}
