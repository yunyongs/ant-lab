// The page a visitor first asked for. Only router state is read (no query
// parameter), and only an in-app path is accepted, so there is no open redirect.
export function returnTo(state: unknown): string {
  const from = (state as { from?: unknown } | null)?.from;
  if (typeof from !== 'object' || from === null) return '/';
  const { pathname, search, hash } = from as Record<string, unknown>;
  if (
    typeof pathname !== 'string' ||
    !pathname.startsWith('/') ||
    pathname.startsWith('//')
  ) {
    return '/';
  }
  const tail = (part: unknown) => (typeof part === 'string' ? part : '');
  return `${pathname}${tail(search)}${tail(hash)}`;
}
