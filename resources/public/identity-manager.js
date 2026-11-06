// <axxium-identity-manager>: the signed-in actor's identity subtree.
// Every action is one POST /api/identity/admin call through the same
// policy-checked ops as the MCP tools. Secrets are write-only: inputs are
// cleared after submit and nothing secret is ever read back, except a
// bootstrap key, which is shown once.
import { treeRows, policyFromFields, fieldsFromPolicy } from './identity-tree.js';

const KINDS = ['agent', 'bot', 'host', 'group'];
const TYPES = ['api-key', 'ssh-key', 'github-pat', 'github-app'];

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
    else node.setAttribute(k, v);
  }
  node.append(...children.filter(c => c != null));
  return node;
}

class AxxiumIdentityManager extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({ mode: 'open' });
    this.actors = [];
    this.selected = '';
    this.message = '';
    this.unavailable = false;
    this.bootstrap = null;   // {did, key} — shown once, never refetched
    this.exported = '';
  }

  connectedCallback() { this.refresh(); }

  async admin(body) {
    const response = await fetch('/api/identity/admin', {
      method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    });
    let result = {};
    try { result = await response.json(); } catch (_) { /* bodyless error */ }
    if (!response.ok) throw Object.assign(new Error(result.error || `HTTP ${response.status}`), { status: response.status });
    return result;
  }

  async refresh() {
    try {
      this.actors = (await this.admin({ op: 'list' })).actors;
      this.unavailable = false;
    } catch (error) {
      this.actors = [];
      this.unavailable = error.status === 401 || error.status === 403 || error.status === 503;
      if (!this.unavailable) this.message = error.message;
    }
    this.render();
  }

  async run(action) {
    this.message = '';
    try { await action(); } catch (error) { this.message = error.message; }
    await this.refresh();
  }

  async startManifest(did) {
    const response = await fetch('/api/github/manifest', {
      method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ did }),
    });
    if (!response.ok) throw new Error((await response.json().catch(() => ({}))).error || `HTTP ${response.status}`);
    const html = await response.text();
    const win = window.open('', '_blank');
    win.document.write(html);
    win.document.close();
  }

  actorPanel(a) {
    const panel = el('div', { class: 'panel' });
    const fields = fieldsFromPolicy(a.policy);
    const input = (name, label, value = '', type = 'text') =>
      el('label', {}, label, el('input', { name, type, value, autocomplete: 'off' }));
    const submit = (op, extra = () => ({})) => event => {
      event.preventDefault();
      const form = event.currentTarget;
      const data = Object.fromEntries(new FormData(form));
      form.reset();                         // secrets never linger in the DOM
      this.run(() => this.admin({ op, ...data, ...extra(data) }));
    };

    panel.append(
      el('h3', {}, `${a.name || a.did} `, el('small', {}, `${a.kind} · ${a.status}${a.effective ? '' : ' (not effective)'}`)),
      el('small', {}, a.did),
      el('p', {}, `Repos: ${(a.policy?.repos || []).join(', ')} · actions: ${(a.policy?.actions || []).join(', ')} · forbid: ${(a.policy?.forbid || []).join(', ')}`));

    const creds = el('div');
    for (const c of a.credentials || []) {
      creds.append(el('div', { class: 'row' },
        el('span', {}, `${c.label} `, el('small', {}, `${c.status} · gen ${c.generation ?? 1}${c.appSlug ? ' · ' + c.appSlug : ''}`)),
        c.status === 'active' && c.label !== 'bootstrap'
          ? el('form', { onsubmit: submit('credential-rotate', () => ({ did: a.did, label: c.label })) },
              el('input', { name: 'secret', type: 'password', placeholder: 'new secret', required: '', autocomplete: 'new-password' }),
              el('button', {}, 'Rotate'),
              el('button', { type: 'button', class: 'secondary',
                onclick: () => this.run(() => this.admin({ op: 'credential-revoke', did: a.did, label: c.label })) }, 'Revoke'))
          : null));
    }
    panel.append(el('h4', {}, 'Credentials'), creds);

    panel.append(el('details', {}, el('summary', {}, 'Add credential (write-only)'),
      el('form', { onsubmit: submit('credential-provision', () => ({ did: a.did })) },
        input('label', 'Label'),
        el('label', {}, 'Type', el('select', { name: 'type' }, ...TYPES.map(t => el('option', { value: t }, t)))),
        el('label', {}, 'Secret (PEM, key or token)', el('textarea', { name: 'secret', required: '', autocomplete: 'off' })),
        input('env', 'Env var (optional, e.g. OPENAI_API_KEY)'),
        input('provider', 'Provider (optional)'),
        el('button', {}, 'Provision'))));

    panel.append(el('details', {}, el('summary', {}, 'Create child actor'),
      el('form', { onsubmit: submit('create', d => ({ policy: policyFromFields(d) })) },
        input('slug', 'Slug (a-z, 0-9, -)'), input('name', 'Display name'),
        el('label', {}, 'Kind', el('select', { name: 'kind' }, ...KINDS.map(k => el('option', { value: k }, k)))),
        el('small', {}, 'Policy starts as this actor\'s; it may only narrow.'),
        input('repos', 'Repos', fields.repos), input('permissions', 'Permissions (name=level)', fields.permissions),
        input('actions', 'Actions', fields.actions), input('forbid', 'Forbid', fields.forbid),
        el('button', {}, 'Create'))));

    const act = (label, op, cls = '') => el('button', { class: cls,
      onclick: () => this.run(() => this.admin({ op, did: a.did })) }, label);
    panel.append(el('div', { class: 'actions' },
      a.status === 'active' ? act('Suspend', 'suspend', 'secondary') : null,
      a.status === 'suspended' ? act('Resume', 'resume', 'secondary') : null,
      a.status !== 'revoked' ? act('Revoke actor', 'revoke', 'danger') : null,
      el('button', { onclick: () => this.run(async () => {
        const { bootstrapKey } = await this.admin({ op: 'bootstrap-issue', did: a.did });
        this.bootstrap = { did: a.did, key: bootstrapKey };
      }) }, 'Issue bootstrap key'),
      el('button', { onclick: () => this.run(() => this.startManifest(a.did)) }, 'Create GitHub App')));
    return panel;
  }

  render() {
    const root = this.shadowRoot;
    root.innerHTML = `<style>
      :host { display:block; color:#e9e9f2; font:16px system-ui,sans-serif; text-align:left; }
      * { box-sizing:border-box; }
      section { background:#171827; border:1px solid #343650; border-radius:16px; padding:1.25rem; margin:1rem 0; }
      h2 { margin:0 0 .8rem; font-size:1.2rem; } h3,h4 { margin:.8rem 0 .3rem; }
      p { line-height:1.5; color:#b6b8c9; }
      label { display:block; margin:.6rem 0; }
      input,select,textarea { width:100%; padding:.6rem; margin-top:.25rem; color:#fff; background:#10111c; border:1px solid #626581; border-radius:8px; font:inherit; }
      button { display:inline-block; margin:.4rem .4rem .2rem 0; padding:.55rem .9rem; border:0; border-radius:8px; background:#7773d8; color:#fff; cursor:pointer; font:inherit; }
      button.secondary { background:#353855; } button.danger { background:#8a3a4a; } button.tree { background:none; color:#e9e9f2; text-align:left; padding:.3rem 0; }
      .row { display:flex; align-items:center; justify-content:space-between; gap:1rem; border-top:1px solid #343650; padding:.5rem 0; }
      .row form { display:flex; gap:.3rem; align-items:center; } .row input { width:12rem; margin:0; }
      .error { color:#ffb4b4; } small { color:#a7a9c2; }
      code { display:block; overflow-wrap:anywhere; padding:1rem; background:#10111c; white-space:pre-wrap; }
    </style><div id="content"></div>`;
    const content = root.getElementById('content');
    if (this.unavailable) return;
    const section = el('section', {}, el('h2', {}, 'Identities'));
    if (this.message) section.append(el('p', { class: 'error' }, this.message));
    if (this.bootstrap) {
      section.append(el('div', {},
        el('h3', {}, 'Bootstrap key — copy it now, it will not be shown again'),
        el('small', {}, this.bootstrap.did), el('code', {}, this.bootstrap.key),
        el('button', { onclick: () => navigator.clipboard.writeText(this.bootstrap.key) }, 'Copy'),
        el('button', { class: 'secondary', onclick: () => { this.bootstrap = null; this.render(); } }, 'Dismiss')));
    }
    for (const row of treeRows(this.actors)) {
      section.append(el('div', { style: `padding-left:${row.depth * 1.2}rem` },
        el('button', { class: 'tree', onclick: () => { this.selected = row.did; this.render(); } },
          `${row.did === this.selected ? '▸ ' : ''}${row.name || row.did} `,
          el('small', {}, `${row.kind} · ${row.status}`))));
    }
    const selected = this.actors.find(a => a.did === this.selected);
    if (selected) section.append(this.actorPanel(selected));

    section.append(el('details', {}, el('summary', {}, 'Export / import subtree (EDN, no secrets)'),
      el('button', { onclick: () => this.run(async () => { this.exported = (await this.admin({ op: 'export' })).edn; }) }, 'Export'),
      this.exported ? el('code', {}, this.exported) : null,
      el('form', { onsubmit: event => {
          event.preventDefault();
          const seed = new FormData(event.currentTarget).get('seed');
          this.run(async () => {
            const r = await this.admin({ op: 'import', seed });
            this.message = `Imported: ${r.created.length} created, ${r.unchanged.length} unchanged, ${r.refused.length} refused`;
          });
        } },
        el('label', {}, 'Seed EDN', el('textarea', { name: 'seed', rows: '6' })),
        el('button', {}, 'Import'))));
    content.append(section);
  }
}

customElements.define('axxium-identity-manager', AxxiumIdentityManager);

