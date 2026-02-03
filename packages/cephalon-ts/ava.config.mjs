// AVA configuration for cephalon-ts
// Only runs tests within this package directory

export default {
  files: [
    'build/**/*.test.js',
    '!**/REDACTED_SECRET_modules/**',
    '!**/dist/**'
  ],
  REDACTED_SECRETArguments: [
    '--no-warnings',
  ],
  timeout: '2m',
  environmentVariables: {
    NODE_ENV: 'test',
  },
};
