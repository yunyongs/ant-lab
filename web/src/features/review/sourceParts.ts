export type SourcePart =
  { kind: 'link'; href: string; n: number } | { kind: 'text'; text: string };

const HTTP_URL = /^https?:\/\/\S+$/;

// `source` is free text: several URLs joined by ";" or a bibliography line.
// Only http(s) parts become links; anything else (including javascript:) is
// plain text, never an href.
export function sourceParts(source: string): SourcePart[] {
  const parts: SourcePart[] = [];
  let links = 0;
  for (const raw of source.split(';')) {
    const text = raw.trim();
    if (text === '') continue;
    if (HTTP_URL.test(text)) {
      links += 1;
      parts.push({ kind: 'link', href: text, n: links });
    } else {
      parts.push({ kind: 'text', text });
    }
  }
  return parts;
}
