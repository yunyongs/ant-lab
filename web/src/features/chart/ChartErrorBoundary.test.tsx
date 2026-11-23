import { render, screen } from '@testing-library/react';
import { lazy, Suspense } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ChartErrorBoundary } from './ChartErrorBoundary.tsx';

afterEach(() => {
  vi.restoreAllMocks();
});

describe('chart error boundary', () => {
  it('shows the fallback when the chart chunk fails to load', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const Missing = lazy(() =>
      Promise.reject(new Error('Failed to fetch dynamically imported module')),
    );
    render(
      <div>
        <ChartErrorBoundary fallback={<p>대체 문구</p>}>
          <Suspense fallback={null}>
            <Missing />
          </Suspense>
        </ChartErrorBoundary>
        <p>옆 내용</p>
      </div>,
    );
    expect(await screen.findByText('대체 문구')).toBeInTheDocument();
    expect(screen.getByText('옆 내용')).toBeInTheDocument();
  });

  it('renders the children while nothing fails', () => {
    render(
      <ChartErrorBoundary fallback={<p>대체 문구</p>}>
        <p>그래프</p>
      </ChartErrorBoundary>,
    );
    expect(screen.getByText('그래프')).toBeInTheDocument();
    expect(screen.queryByText('대체 문구')).not.toBeInTheDocument();
  });
});
