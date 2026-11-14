import { randomBytes } from 'node:crypto';

export interface Member {
  email: string;
  name: string;
  password: string;
}

// A fresh member per project and run: no EMAIL_TAKEN on reruns, no SEASON_LIMIT.
// The password is 24 URL-safe characters (the API takes 12 to 64) and is never logged.
export function newMember(project: string): Member {
  const stamp = new Date().toISOString().replace(/\D/g, '').slice(0, 14);
  return {
    email: `e2e-${project}-${stamp}-${randomBytes(2).toString('hex')}@example.test`,
    name: '종단 시험',
    password: randomBytes(18).toString('base64url'),
  };
}
