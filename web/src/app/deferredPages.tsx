import { lazy, Suspense, type ReactNode } from 'react';

// Pages of a finished season load on demand: they are opened once per season
// and keep the main bundle small.
const ReportPage = lazy(async () => ({
  default: (await import('../pages/ReportPage.tsx')).ReportPage,
}));
const ReviewPage = lazy(async () => ({
  default: (await import('../pages/ReviewPage.tsx')).ReviewPage,
}));

function Deferred({ children }: { children: ReactNode }) {
  return (
    <Suspense fallback={<p role="status">화면을 불러오는 중입니다…</p>}>
      {children}
    </Suspense>
  );
}

export function DeferredReportPage() {
  return (
    <Deferred>
      <ReportPage />
    </Deferred>
  );
}

export function DeferredReviewPage() {
  return (
    <Deferred>
      <ReviewPage />
    </Deferred>
  );
}
