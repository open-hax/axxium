// Closed shared-login destinations: an interaction or initial device verification.
// The final negative lookahead requires absolute end, including after newlines.
export function safeInteractionReturn(value) {
  return typeof value === 'string' && (
    /^\/oidc\/interaction\/[A-Za-z0-9_-]{1,128}(?![\s\S])/.test(value) ||
    /^\/oidc\/device(?:\?user_code=[A-Za-z0-9-]{1,64})?(?![\s\S])/.test(value))
    ? value : null;
}
