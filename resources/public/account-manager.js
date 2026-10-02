// A self-contained account control usable wherever Axxium is served at the same origin.
class AxxiumAccountManager extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({ mode: 'open' });
    this.actor = null;
    this.actors = [];
    this.googleEnabled = false;
    this.atprotoEnabled = false;
    this.message = '';
    this.issuedToken = '';
    this.credentialPanelActor = '';
    this.credentials = [];
  }

  async connectedCallback() {
    await this.refresh();
  }

  async request(path, method = 'GET', body) {
    const response = await fetch(path, {
      method,
      credentials: 'same-origin',
      headers: body ? { 'Content-Type': 'application/json' } : {},
      body: body ? JSON.stringify(body) : undefined,
    });
    const result = await response.json();
    if (!response.ok) {
      throw Object.assign(new Error(result.error || `HTTP ${response.status}`), { status: response.status });
    }
    return result;
  }

  async refresh() {
    try {
      const config = await this.request('/api/auth/config');
      this.googleEnabled = config.googleEnabled === true;
      this.atprotoEnabled = config.atprotoEnabled === true;
      const me = await this.request('/api/auth/me');
      this.actor = me.actor;
      try {
        this.actors = (await this.request('/api/actors')).actors;
        if (this.credentialPanelActor) {
          this.credentials = (await this.request(`/api/actors/${encodeURIComponent(this.credentialPanelActor)}/credentials`)).credentials;
        }
      } catch (error) {
        if (error.status !== 403) this.message = error.message;
        this.actors = [];
      }
    } catch (_) {
      this.actor = null;
      this.actors = [];
    }
    this.render();
  }

  async run(action) {
    try {
      this.message = '';
      await action();
    } catch (error) {
      this.message = error.message;
    }
    await this.refresh();
  }

  render() {
    const root = this.shadowRoot;
    root.innerHTML = `<style>
      :host { display:block; color:#e9e9f2; font:16px system-ui,sans-serif; }
      * { box-sizing:border-box; }
      section { background:#171827; border:1px solid #343650; border-radius:16px; padding:1.25rem; margin:1rem 0; }
      h2 { margin:0 0 .8rem; font-size:1.2rem; }
      p { line-height:1.5; color:#b6b8c9; }
      label { display:block; margin:.6rem 0; }
      input { width:100%; padding:.7rem; margin-top:.25rem; color:#fff; background:#10111c; border:1px solid #626581; border-radius:8px; }
      button,a.button { display:inline-block; margin:.4rem .4rem .2rem 0; padding:.65rem 1rem; border:0; border-radius:8px; background:#7773d8; color:#fff; cursor:pointer; text-decoration:none; font:inherit; }
      button.secondary { background:#353855; }
      .row { display:flex; align-items:center; justify-content:space-between; gap:1rem; border-top:1px solid #343650; padding:.7rem 0; }
      .row .actions { display:flex; flex-wrap:wrap; justify-content:flex-end; }
      .error { color:#ffb4b4; }
      code { display:block; overflow-wrap:anywhere; padding:1rem; background:#10111c; }
      small { color:#a7a9c2; }
    </style><div id="content"></div>`;
    const content = root.getElementById('content');
    if (this.message) {
      const error = document.createElement('p');
      error.className = 'error';
      error.textContent = this.message;
      content.append(error);
    }
    if (!this.actor) {
      const section = document.createElement('section');
      section.innerHTML = `<h2>Enter Axxium</h2><form id="login">
        <label>Email<input name="email" type="email" autocomplete="username" required></label>
        <label>Password<input name="password" type="password" autocomplete="current-password" required></label>
        <button>Sign in</button></form>`;
      section.querySelector('form').addEventListener('submit', event => {
        event.preventDefault();
        const form = event.currentTarget;
        this.run(async () => {
          await this.request('/api/auth/login', 'POST', {
            email: form.elements.email.value,
            password: form.elements.password.value,
          });
          form.elements.password.value = '';
        });
      });
      if (this.googleEnabled) {
        const link = document.createElement('a');
        link.className = 'button';
        link.href = '/api/auth/google/start';
        link.textContent = 'Continue with Google';
        section.append(link);
      }
      if (this.atprotoEnabled) {
        const form = document.createElement('form');
        form.innerHTML = `<label>AT Protocol handle or DID<input name="identity" autocomplete="username" placeholder="you.bsky.social" required maxlength="512"></label><button>Continue with AT Protocol</button>`;
        form.addEventListener('submit', event => {
          event.preventDefault();
          const identity = form.elements.identity.value.trim();
          if (identity) location.assign(`/api/auth/atproto/start?identity=${encodeURIComponent(identity)}`);
        });
        section.append(form);
      }
      content.append(section);
      return;
    }
    const identity = document.createElement('section');
    const title = document.createElement('h2');
    title.textContent = this.actor.display_name || this.actor.email || this.actor.id;
    const detail = document.createElement('p');
    detail.textContent = `${this.actor.id} · ${(this.actor.roles || []).join(', ') || 'user'}`;
    const logout = document.createElement('button');
    logout.className = 'secondary';
    logout.textContent = 'Sign out';
    logout.addEventListener('click', () => this.run(async () => {
      await this.request('/api/auth/logout', 'POST', {});
      this.issuedToken = '';
    }));
    identity.append(title, detail, logout);
    content.append(identity);
    if (this.atprotoEnabled) {
      const link = document.createElement('section');
      link.innerHTML = `<h2>Link an AT identity</h2><p>Keep this Axxium actor when signing in with your AT Protocol DID.</p><form><label>AT Protocol handle or DID<input name="identity" autocomplete="username" placeholder="you.bsky.social" required maxlength="512"></label><button>Link identity</button></form>`;
      link.querySelector('form').addEventListener('submit', event => {
        event.preventDefault();
        const identity = event.currentTarget.elements.identity.value.trim();
        if (identity) location.assign(`/api/auth/atproto/start?link=1&identity=${encodeURIComponent(identity)}`);
      });
      content.append(link);
    }
    if (!(this.actor.roles || []).includes('axxium/system-admin')) return;
    const controls = document.createElement('section');
    controls.innerHTML = `<h2>Actors</h2><p>Create an agent, then issue a credential with a short lifetime. The secret appears once.</p>
      <form id="create-agent"><label>Agent name<input name="name" required maxlength="200"></label><button>Create agent</button></form>
      <div id="actors"></div>`;
    controls.querySelector('form').addEventListener('submit', event => {
      event.preventDefault();
      const form = event.currentTarget;
      this.run(() => this.request('/api/actors/agents', 'POST', {
        'display-name': form.elements.name.value,
      }));
    });
    const list = controls.querySelector('#actors');
    for (const actor of this.actors) {
      const row = document.createElement('div');
      row.className = 'row';
      const label = document.createElement('span');
      label.textContent = `${actor.display_name || actor.email || actor.id} · ${actor.kind}`;
      row.append(label);
      if (actor.kind === 'agent') {
        const actions = document.createElement('span');
        actions.className = 'actions';
        const issue = document.createElement('button');
        issue.textContent = 'Issue 24h credential';
        issue.addEventListener('click', () => this.run(async () => {
          const result = await this.request(`/api/actors/${encodeURIComponent(actor.id)}/credentials`, 'POST', {
            label: 'Account manager', 'expires-in-hours': 24,
          });
          this.issuedToken = result.token;
        }));
        const inspect = document.createElement('button');
        inspect.className = 'secondary';
        inspect.textContent = 'Credentials';
        inspect.addEventListener('click', () => this.run(async () => {
          this.credentialPanelActor = actor.id;
          this.credentials = (await this.request(`/api/actors/${encodeURIComponent(actor.id)}/credentials`)).credentials;
        }));
        actions.append(inspect, issue);
        row.append(actions);
      }
      list.append(row);
    }
    content.append(controls);
    if (this.credentialPanelActor) {
      const panel = document.createElement('section');
      const heading = document.createElement('h2');
      heading.textContent = 'Agent credentials';
      panel.append(heading);
      for (const credential of this.credentials) {
        const row = document.createElement('div');
        row.className = 'row';
        const label = document.createElement('span');
        label.textContent = `${credential.label} · expires ${new Date(credential.expires_at).toLocaleString()}${credential.revoked_at ? ' · revoked' : ''}`;
        row.append(label);
        if (!credential.revoked_at) {
          const revoke = document.createElement('button');
          revoke.className = 'secondary';
          revoke.textContent = 'Revoke';
          revoke.addEventListener('click', () => this.run(async () => {
            await this.request(`/api/actors/${encodeURIComponent(this.credentialPanelActor)}/credentials/${encodeURIComponent(credential.id)}`, 'DELETE');
          }));
          row.append(revoke);
        }
        panel.append(row);
      }
      content.append(panel);
    }
    if (this.issuedToken) {
      const once = document.createElement('section');
      const heading = document.createElement('h2');
      heading.textContent = 'New credential — copy it now';
      const code = document.createElement('code');
      code.textContent = this.issuedToken;
      const copy = document.createElement('button');
      copy.textContent = 'Copy credential';
      copy.addEventListener('click', () => navigator.clipboard.writeText(this.issuedToken));
      const dismiss = document.createElement('button');
      dismiss.className = 'secondary';
      dismiss.textContent = 'Dismiss';
      dismiss.addEventListener('click', () => { this.issuedToken = ''; this.render(); });
      once.append(heading, code, copy, dismiss);
      content.append(once);
    }
  }
}

customElements.define('axxium-account-manager', AxxiumAccountManager);
