const MONEY =
  'Money values are decimal strings and must not be parsed into floating-point numbers; show the server string as is.';
const HTML =
  'Server text is untrusted: render it as a text child, never as raw HTML.';
const REVIEW =
  'Review-only labels and reveal text must not reach in-season screens by reuse.';
const TOKEN =
  'Access tokens live in memory only; browser storage and cookies are not allowed.';

/** @type {import('eslint').Linter.RulesRecord} */
export const restrictionRules = {
  'no-restricted-globals': [
    'error',
    { name: 'parseFloat', message: MONEY },
    { name: 'localStorage', message: TOKEN },
    { name: 'sessionStorage', message: TOKEN },
    { name: 'indexedDB', message: TOKEN },
  ],
  'no-restricted-properties': [
    'error',
    { object: 'Number', property: 'parseFloat', message: MONEY },
    { object: 'Number', property: 'call', message: MONEY },
    { object: 'Number', property: 'apply', message: MONEY },
    { object: 'Number', property: 'bind', message: MONEY },
    { object: 'window', property: 'parseFloat', message: MONEY },
    { object: 'globalThis', property: 'parseFloat', message: MONEY },
    { object: 'window', property: 'Number', message: MONEY },
    { object: 'globalThis', property: 'Number', message: MONEY },
    { object: 'window', property: 'localStorage', message: TOKEN },
    { object: 'window', property: 'sessionStorage', message: TOKEN },
    { object: 'globalThis', property: 'localStorage', message: TOKEN },
    { object: 'globalThis', property: 'sessionStorage', message: TOKEN },
    { object: 'window', property: 'indexedDB', message: TOKEN },
    { object: 'globalThis', property: 'indexedDB', message: TOKEN },
    { object: 'document', property: 'cookie', message: TOKEN },
  ],
  'no-restricted-syntax': [
    'error',
    { selector: "CallExpression[callee.name='Number']", message: MONEY },
    { selector: "NewExpression[callee.name='Number']", message: MONEY },
    { selector: "UnaryExpression[operator='+']", message: MONEY },
    { selector: "UnaryExpression[operator='~']", message: MONEY },
    { selector: "VariableDeclarator[init.name='Number']", message: MONEY },
    { selector: "AssignmentExpression[right.name='Number']", message: MONEY },
    {
      selector: "CallExpression > Identifier.arguments[name='Number']",
      message: MONEY,
    },
    {
      selector: "JSXAttribute[name.name='dangerouslySetInnerHTML']",
      message: HTML,
    },
    {
      selector: "Property[key.name='dangerouslySetInnerHTML']",
      message: HTML,
    },
    {
      selector: "Property[key.value='dangerouslySetInnerHTML']",
      message: HTML,
    },
    {
      selector:
        'AssignmentExpression[left.property.name=/^(inner|outer)HTML$/]',
      message: HTML,
    },
  ],
};

// In-season screens (feed, ranking) must not import the review folder.
/** @type {import('eslint').Linter.Config[]} */
export const importRestrictions = [
  {
    files: ['src/features/feed/**', 'src/features/ranking/**'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [{ group: ['**/review', '**/review/**'], message: REVIEW }],
        },
      ],
    },
  },
];
