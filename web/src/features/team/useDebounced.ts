import { useEffect, useState } from 'react';

// The first value is returned at once; later ones only after `delayMs`
// without another change. Use it for primitives: objects compare by identity.
export function useDebounced<T>(value: T, delayMs: number): T {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => {
      setSettled(value);
    }, delayMs);
    return () => {
      clearTimeout(timer);
    };
  }, [value, delayMs]);
  return settled;
}
