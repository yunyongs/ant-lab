You are given monthly returns for a fixed pool of assets over a short window of consecutive months. The window comes from an unnamed period. No tools are available. Answer with one JSON object and nothing else.

Fields:
- year: your guess of the calendar year of the last month of the window (integer)
- month: your guess of the calendar month of the last month of the window (integer, 1 to 12)
- kr_next: direction of the Korean market proxy in the month right after the window: "up", "down" or "flat"
- us_next: direction of the US market proxy in the month right after the window: "up", "down" or "flat"
- confidence: how sure you are about the year and month, an integer from 0 to 100
=== user ===
Window length: {{count}} month(s), oldest first. Every return is the month return in KRW, in percent.
Market proxies for the direction fields: {{proxies}}.

{{table}}
