const byId = (id) => document.getElementById(id);
let transfer = '';
let destination = '';
const notify = (message, error = false) => {
  byId('notice').textContent = message;
  byId('notice').classList.toggle('error', error);
};
async function api(path, body) {
  const response = await fetch(path, { method: body ? 'POST' : 'GET', credentials: 'same-origin',
    headers: body ? { 'Content-Type': 'application/json' } : {}, body: body ? JSON.stringify(body) : undefined });
  const result = await response.json();
  if (!response.ok) throw new Error(result.error || 'This operation could not be completed.');
  return result;
}
function showActor(actor) {
  transfer = '';
  byId('transfer-ready').hidden = true;
  byId('signed-out').hidden = Boolean(actor);
  byId('signed-in').hidden = !actor;
  if (!actor) return;
  byId('actor-name').textContent = actor.display_name;
  byId('actor-email').textContent = actor.email;
  byId('actor-id').textContent = actor.id;
  byId('actor-origin').textContent = actor.origin_issuer || 'This instance';
}
for (const [formId, path, message] of [
  ['signup', '/api/auth/signup', 'Account registered on this instance.'],
  ['login', '/api/auth/login', 'Signed in with your account on this instance.'],
  ['import', '/api/identity/import', 'Identity registered here. Your identity is preserved and your account is independent.'],
  ['export', '/api/identity/export', 'Identity transfer ready for the selected destination.']
]) {
  byId(formId).addEventListener('submit', async (event) => {
    event.preventDefault();
    const form = event.currentTarget;
    const button = form.querySelector('button'); button.disabled = true;
    const values = Object.fromEntries(new FormData(form));
    try {
      const result = await api(path, values);
      form.reset();
      if (result.actor) showActor(result.actor);
      if (result.transfer) {
        transfer = result.transfer; destination = result.recipient;
        byId('transfer-ready').hidden = false;
        byId('destination').href = `${destination}/portal/index.html`;
      }
      notify(message);
    } catch (error) { notify(error.message, true); }
    finally { button.disabled = false; }
  });
}
byId('copy-transfer').addEventListener('click', async () => {
  try { await navigator.clipboard.writeText(transfer); notify('Identity transfer copied. Open the destination and choose “Bring an identity to this computer”.'); }
  catch { notify('Clipboard access was denied. Allow clipboard access and try again.', true); }
});
byId('logout').addEventListener('click', async () => {
  try { await api('/api/auth/logout', {}); showActor(null); notify('Signed out.'); }
  catch (error) { notify(error.message, true); }
});
try {
  const config = await api('/api/auth/config');
  byId('instance').textContent = new URL(config.publicBaseUrl).hostname;
  for (const recipient of config.recipients) {
    const option = document.createElement('option'); option.value = recipient; option.textContent = new URL(recipient).hostname;
    byId('recipients').append(option);
  }
  try { showActor((await api('/api/auth/me')).actor); } catch { showActor(null); }
} catch (error) { notify(error.message, true); }
