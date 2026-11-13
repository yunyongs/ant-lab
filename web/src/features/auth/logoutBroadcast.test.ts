import { describe, expect, it, vi } from 'vitest';
import { announceLogout, onLogoutFromOtherTab } from './logoutBroadcast.ts';

describe('logout broadcast', () => {
  it('delivers only a bare logout message to another instance', async () => {
    const other = new BroadcastChannel('antlab-auth');
    const received = new Promise<unknown>((resolve) => {
      other.addEventListener('message', (e: MessageEvent) => {
        resolve(e.data);
      });
    });
    announceLogout();
    expect(await received).toEqual({ type: 'logout' });
    other.close();
  });

  it('calls back when another instance announces a logout', async () => {
    const fn = vi.fn();
    const stop = onLogoutFromOtherTab(fn);
    const other = new BroadcastChannel('antlab-auth');
    other.postMessage({ type: 'logout' });
    other.postMessage({ type: 'something-else' });
    await vi.waitFor(() => {
      expect(fn).toHaveBeenCalledTimes(1);
    });
    stop();
    other.postMessage({ type: 'logout' });
    await new Promise((r) => setTimeout(r, 20));
    expect(fn).toHaveBeenCalledTimes(1);
    other.close();
  });

  it('does not hear its own announcement', async () => {
    const fn = vi.fn();
    const stop = onLogoutFromOtherTab(fn);
    announceLogout();
    await new Promise((r) => setTimeout(r, 20));
    expect(fn).not.toHaveBeenCalled();
    stop();
  });
});
