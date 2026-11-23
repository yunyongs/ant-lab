import { Component, type ReactNode } from 'react';

// A chart is optional: a failed chunk load (offline, or a stale page after a
// redeploy) or a library error replaces only the chart, never the screen.
export class ChartErrorBoundary extends Component<
  { fallback: ReactNode; children: ReactNode },
  { failed: boolean }
> {
  override state = { failed: false };

  static getDerivedStateFromError(): { failed: boolean } {
    return { failed: true };
  }

  override render() {
    return this.state.failed ? this.props.fallback : this.props.children;
  }
}
