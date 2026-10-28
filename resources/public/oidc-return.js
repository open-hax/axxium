// Closed shared-login destinations: an interaction or initial device verification.
// The final negative lookahead requires absolute end, including after newlines.
export function safeInteractionReturn(value) {
  if (typeof value !== 'string') return null;
  if (value === '/oidc/device') return '/oidc/device';
  const interaction = /^\/oidc\/interaction\/([A-Za-z0-9_-]{1,128})(?![\s\S])/.exec(value);
  if (interaction) return `/oidc/interaction/${encodeURIComponent(interaction[1])}`;
  const device = /^\/oidc\/device\?user_code=([A-Za-z0-9-]{1,64})(?![\s\S])/.exec(value);
  return device ? `/oidc/device?user_code=${encodeURIComponent(device[1])}` : null;
}
