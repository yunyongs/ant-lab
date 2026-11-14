import { describe, expect, it } from 'vitest';
import { sourceParts } from './sourceParts.ts';

describe('sourceParts', () => {
  it('turns one URL into one link part', () => {
    expect(sourceParts('https://a.test/x')).toEqual([
      { kind: 'link', href: 'https://a.test/x', n: 1 },
    ]);
  });

  it('splits on ";", trims, numbers the links and drops empty parts', () => {
    expect(sourceParts('https://a.test ; http://b.test ;; ')).toEqual([
      { kind: 'link', href: 'https://a.test', n: 1 },
      { kind: 'link', href: 'http://b.test', n: 2 },
    ]);
  });

  it('keeps a bibliography line as text', () => {
    expect(sourceParts('서지: 책 이름, 2010')).toEqual([
      { kind: 'text', text: '서지: 책 이름, 2010' },
    ]);
  });

  it.each(['javascript:alert(1)', 'ftp://x.test', 'https://a b'])(
    'never makes %s a link',
    (text) => {
      expect(sourceParts(text).every((part) => part.kind === 'text')).toBe(
        true,
      );
    },
  );
});
