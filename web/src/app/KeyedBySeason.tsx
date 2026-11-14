import { Fragment, type ReactNode } from 'react';
import { useParams } from 'react-router';

// Gives each season its own page state: a notice or an open sheet must not
// follow the player from one season to another.
export function KeyedBySeason({ children }: { children: ReactNode }) {
  const { seasonId } = useParams();
  return <Fragment key={seasonId}>{children}</Fragment>;
}
