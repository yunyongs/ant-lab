const MONEY =
  'Money values are decimal strings and must not be parsed into floating-point numbers; show the server string as is.';
const TOKEN =
  'Access tokens live in memory only; browser storage and cookies are not allowed.';

/** @type {import('eslint').Linter.RulesRecord} */
export const restrictionRules = {
  'no-restricted-globals': [
    'error',
    { name: 'parseFloat', message: MONEY },
    { name: 'localStorage', message: TOKEN },
    { name: 'sessionStorage', message: TOKEN },
  ],
  'no-restricted-properties': [
    'error',
    { object: 'Number', property: 'parseFloat', message: MONEY },
    { object: 'window', property: 'localStorage', message: TOKEN },
    { object: 'window', property: 'sessionStorage', message: TOKEN },
    { object: 'globalThis', property: 'localStorage', message: TOKEN },
    { object: 'globalThis', property: 'sessionStorage', message: TOKEN },
    { object: 'document', property: 'cookie', message: TOKEN },
  ],
  'no-restricted-syntax': [
    'error',
    { selector: "CallExpression[callee.name='Number']", message: MONEY },
    { selector: "NewExpression[callee.name='Number']", message: MONEY },
  ],
};
