// Shared-login return destinations are only opaque same-origin interaction paths.
export function safeInteractionReturn(value) {
  return typeof value === 'string' && /^\/oidc\/interaction\/[A-Za-z0-9_-]{1,128}$/.test(value)
    ? value : null;
}
