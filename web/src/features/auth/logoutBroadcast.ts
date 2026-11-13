const CHANNEL = 'antlab-auth';

let channel: BroadcastChannel | null = null;

// One shared instance: a channel never receives its own messages, so the tab
// that logs out does not announce to itself.
function shared(): BroadcastChannel | null {
  if (typeof BroadcastChannel === 'undefined') return null;
  channel ??= new BroadcastChannel(CHANNEL);
  return channel;
}

export function announceLogout(): void {
  shared()?.postMessage({ type: 'logout' });
}

export function onLogoutFromOtherTab(fn: () => void): () => void {
  const target = shared();
  if (target === null) return () => undefined;
  const listener = (event: MessageEvent) => {
    const data = event.data as { type?: unknown } | null;
    if (data?.type === 'logout') fn();
  };
  target.addEventListener('message', listener);
  return () => {
    target.removeEventListener('message', listener);
  };
}
