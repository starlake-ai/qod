import { useCallback, useEffect, useState } from 'react';
import { api, ApiError, errorMessage } from '../api/client';
import type { PlatformPermissionView, PlatformRoleView, UserResponse } from '../api/types';
import { groupByModule } from '../api/platformRoles';

function toggle(list: string[], name: string): string[] {
  return list.includes(name) ? list.filter(x => x !== name) : [...list, name];
}

interface RoleForm { id: string | null; name: string; description: string; permissions: string[] }
const EMPTY_ROLE: RoleForm = { id: null, name: '', description: '', permissions: [] };

export default function Staff() {
  const [perms, setPerms] = useState<PlatformPermissionView[]>([]);
  const [roles, setRoles] = useState<PlatformRoleView[]>([]);
  const [users, setUsers] = useState<UserResponse[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [form, setForm] = useState<RoleForm>(EMPTY_ROLE);
  const [newUser, setNewUser] = useState({ username: '', password: '', roles: [] as string[] });
  const [editingUser, setEditingUser] = useState<{ id: string; roles: string[] } | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setErr(null);
    Promise.all([api.platformPermissions(), api.platformRoles(), api.staffUsers()])
      .then(([p, r, u]) => {
        setPerms(p.permissions);
        setRoles(r.roles);
        setUsers(u.users);
        setLoading(false);
      })
      .catch(e => {
        setErr(e instanceof ApiError && e.status === 403 ? 'Superuser only.' : errorMessage(e));
        setLoading(false);
      });
  }, []);

  useEffect(load, [load]);

  const run = (fn: () => Promise<unknown>, after?: () => void) => {
    setErr(null);
    fn().then(() => { after?.(); load(); }).catch(e => setErr(errorMessage(e)));
  };

  const groups = groupByModule(perms);

  const saveRole = () => {
    const f = form;
    run(
      () => f.id === null
        ? api.createPlatformRole({ name: f.name.trim(), description: f.description, permissions: f.permissions })
        : api.updatePlatformRole({ id: f.id, name: f.name.trim(), description: f.description, permissions: f.permissions }),
      () => setForm(EMPTY_ROLE),
    );
  };

  const removeRole = (r: PlatformRoleView) => {
    if (!window.confirm(`Delete the platform role "${r.name}"?`)) return;
    run(() => api.deletePlatformRole(r.id));
  };

  const createUser = () => {
    const u = newUser;
    run(
      () => api.createUser({ tenant: null, username: u.username.trim(), password: u.password, kind: 'staff', platformRoles: u.roles }),
      () => setNewUser({ username: '', password: '', roles: [] }),
    );
  };

  return (
    <div>
      {loading && <p>Loading...</p>}
      {err && <p style={{ color: 'var(--bad)' }}>{err}</p>}

      <div className="card">
        <h2>Platform roles</h2>
        <table>
          <thead><tr><th>Name</th><th>Description</th><th>Permissions</th><th /></tr></thead>
          <tbody>
            {roles.map(r => (
              <tr key={r.id}>
                <td>{r.name}</td>
                <td>{r.description ?? ''}</td>
                <td>{r.permissions.join(', ')}</td>
                <td>
                  <button onClick={() => setForm({ id: r.id, name: r.name, description: r.description ?? '', permissions: [...r.permissions] })}>Edit</button>{' '}
                  <button onClick={() => removeRole(r)}>Delete</button>
                </td>
              </tr>
            ))}
            {roles.length === 0 && !loading && <tr><td colSpan={4}>No platform roles.</td></tr>}
          </tbody>
        </table>

        <h3>{form.id === null ? 'Create a role' : `Edit role ${form.name}`}</h3>
        <p>
          <input placeholder="Name" value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} />{' '}
          <input placeholder="Description" value={form.description} onChange={e => setForm({ ...form, description: e.target.value })} />
        </p>
        {groups.map(g => (
          <fieldset key={g.module}>
            <legend>{g.module}</legend>
            {g.permissions.map(p => (
              <div key={p.name}>
                <label>
                  <input
                    type="checkbox"
                    checked={form.permissions.includes(p.name)}
                    disabled={!p.declared && !form.permissions.includes(p.name)}
                    onChange={() => setForm({ ...form, permissions: toggle(form.permissions, p.name) })}
                  />{' '}
                  {p.name}
                </label>
                {p.declared && p.description && <span style={{ color: 'var(--text-mute)' }}> {p.description}</span>}
                {!p.declared && <span style={{ color: 'var(--text-mute)' }}> (no module declares it)</span>}
              </div>
            ))}
          </fieldset>
        ))}
        <p>
          <button onClick={saveRole} disabled={!form.name.trim()}>{form.id === null ? 'Create role' : 'Save role'}</button>{' '}
          {form.id !== null && <button onClick={() => setForm(EMPTY_ROLE)}>Cancel</button>}
        </p>
      </div>

      <div className="card">
        <h2>Staff users</h2>
        <table>
          <thead><tr><th>Username</th><th>Enabled</th><th>Platform roles</th><th /></tr></thead>
          <tbody>
            {users.map(u => (
              <tr key={u.id}>
                <td>{u.username}</td>
                <td>{u.enabled ? 'yes' : 'no'}</td>
                <td>
                  {editingUser?.id === u.id ? (
                    <>
                      {roles.map(r => (
                        <label key={r.id} style={{ marginRight: 8 }}>
                          <input
                            type="checkbox"
                            checked={editingUser.roles.includes(r.name)}
                            onChange={() => setEditingUser({ id: u.id, roles: toggle(editingUser.roles, r.name) })}
                          />{' '}
                          {r.name}
                        </label>
                      ))}
                      <button onClick={() => run(() => api.updateUser({ id: u.id, platformRoles: editingUser.roles }), () => setEditingUser(null))}>Save</button>{' '}
                      <button onClick={() => setEditingUser(null)}>Cancel</button>
                    </>
                  ) : (
                    (u.platformRoles ?? []).join(', ')
                  )}
                </td>
                <td>
                  {editingUser?.id !== u.id && (
                    <button onClick={() => setEditingUser({ id: u.id, roles: [...(u.platformRoles ?? [])] })}>Edit roles</button>
                  )}{' '}
                  <button onClick={() => run(() => api.updateUser({ id: u.id, enabled: !u.enabled }))}>
                    {u.enabled ? 'Disable' : 'Enable'}
                  </button>
                </td>
              </tr>
            ))}
            {users.length === 0 && !loading && <tr><td colSpan={4}>No staff users.</td></tr>}
          </tbody>
        </table>

        <h3>Create a staff user</h3>
        <p>
          <input placeholder="Username" value={newUser.username} onChange={e => setNewUser({ ...newUser, username: e.target.value })} />{' '}
          <input type="password" placeholder="Password" value={newUser.password} onChange={e => setNewUser({ ...newUser, password: e.target.value })} />
        </p>
        <p>
          {roles.map(r => (
            <label key={r.id} style={{ marginRight: 8 }}>
              <input
                type="checkbox"
                checked={newUser.roles.includes(r.name)}
                onChange={() => setNewUser({ ...newUser, roles: toggle(newUser.roles, r.name) })}
              />{' '}
              {r.name}
            </label>
          ))}
        </p>
        <button onClick={createUser} disabled={!newUser.username.trim() || !newUser.password}>Create staff user</button>
      </div>
    </div>
  );
}
