import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client';
import type { FederatedSecretResponse, FederatedSourceResponse } from '../api/types';
import { errorsOf, savePluginSource, type PluginSaveApi, type PluginSaveInput } from './pluginSourceSave';

const stored = { id: 'fs-1' } as FederatedSourceResponse;

function secret(name: string, externalRef?: string): FederatedSecretResponse {
  return { id: `s-${name}`, federatedSourceId: 'fs-1', name, value: externalRef ? undefined : '***REDACTED***', externalRef };
}

/** A fake manager. `secrets` is what the source holds, or 'missing' (404) when it does not exist.
 *  `failCreate` is thrown by the enabled create, `failSecret` by every secret write. */
function fake(secrets: FederatedSecretResponse[] | 'missing', o: { failCreate?: unknown; failSecret?: unknown; failList?: unknown } = {}) {
  const calls: string[] = [];
  const api: PluginSaveApi = {
    async createFederatedSource(_t, _d, req) {
      calls.push(req.disabled ? 'create disabled' : 'create');
      if (o.failCreate !== undefined && !req.disabled) throw o.failCreate;
      return stored;
    },
    async listFederatedSecrets() {
      calls.push('list');
      if (o.failList !== undefined) throw o.failList;
      if (secrets === 'missing') throw new ApiError(404, 'not found', 'not_found');
      return { secrets };
    },
    async upsertFederatedSecret(_t, _d, _a, req) {
      calls.push(`secret ${req.name}=${req.value}`);
      if (o.failSecret !== undefined) throw o.failSecret;
      return secret(req.name);
    },
    async deleteFederatedSecret(_t, _d, _a, name) { calls.push(`delete ${name}`); },
  };
  return { api, calls };
}

function input(over: Partial<PluginSaveInput> = {}): PluginSaveInput {
  return {
    tenant: 'acme', tenantDb: 'acme_default', alias: 'docs', description: '',
    sourceType: 'test_connector', pluginConfig: { collection: 'docs' },
    secrets: [{ name: 'TC_TOKEN', value: 'k' }], ...over,
  };
}

describe('savePluginSource', () => {
  it('writes a secret new to a source holding others before the source', async () => {
    const f = fake([secret('TC_TOKEN')]);
    const r = await savePluginSource(f.api, input({
      secrets: [{ name: 'TC_TOKEN', value: 'k' }, { name: 'TC_EXTRA', value: 'e' }],
    }));
    expect(r).toEqual({ ok: true });
    expect(f.calls).toEqual(['list', 'secret TC_EXTRA=e', 'create', 'secret TC_TOKEN=k']);
  });

  it('takes the new secret back when the manager refuses the source', async () => {
    const refusal = new ApiError(403, 'this federated source holds secrets, so only a superuser changes it', 'superuser_required');
    const f = fake([secret('TC_TOKEN')], { failCreate: refusal });
    const r = await savePluginSource(f.api, input({
      secrets: [{ name: 'TC_TOKEN', value: 'k' }, { name: 'TC_EXTRA', value: 'e' }],
    }));
    expect(r).toEqual({ ok: false, errors: ['this federated source holds secrets, so only a superuser changes it'] });
    expect(f.calls).toEqual(['list', 'secret TC_EXTRA=e', 'create', 'delete TC_EXTRA']);
  });

  it('never writes a value over a secret held through an externalRef', async () => {
    const f = fake([secret('TC_TOKEN', 'vault:secret/data/tc#token')]);
    const r = await savePluginSource(f.api, input());
    expect(r).toEqual({
      ok: false,
      errors: ['secret TC_TOKEN is resolved from vault:secret/data/tc#token; change it under Secrets instead of saving a value here'],
    });
    expect(f.calls).toEqual(['list']);
  });

  it('says the new source stays disabled when its secret cannot be written', async () => {
    const f = fake('missing', { failSecret: new ApiError(500, 'boom') });
    const r = await savePluginSource(f.api, input());
    expect(r).toEqual({
      ok: false,
      errors: ['the source was saved DISABLED but its secret TC_TOKEN was not (boom); expand the source, ' +
        'set secret TC_TOKEN under Secrets, then save the source again to enable it'],
    });
    expect(f.calls).toEqual(['list', 'create disabled', 'secret TC_TOKEN=k']);
  });

  it('hands back the manager error message whole, as the manager built it', async () => {
    const f = fake('missing', {
      failCreate: new ApiError(400, 'pluginConfig.a: required; pluginConfig.b: not allowed', 'invalid_federated_source'),
    });
    const r = await savePluginSource(f.api, input({ secrets: [] }));
    expect(r).toEqual({ ok: false, errors: ['pluginConfig.a: required; pluginConfig.b: not allowed'] });
    expect(f.calls).toEqual(['create']);
  });

  it('stops when the secret listing fails for another reason than a missing source', async () => {
    const f = fake('missing', { failList: new ApiError(500, 'down') });
    expect(await savePluginSource(f.api, input())).toEqual({ ok: false, errors: ['down'] });
    expect(f.calls).toEqual(['list']);
  });

  it('requires an alias', async () => {
    const f = fake('missing');
    expect(await savePluginSource(f.api, input({ alias: '  ' }))).toEqual({ ok: false, errors: ['alias is required'] });
    expect(f.calls).toEqual([]);
  });
});

describe('errorsOf', () => {
  it('never splits a message: a provider error may itself contain "; "', () => {
    const credential = 'config: key "apiKey" looks like a literal credential; name a source secret in apiKeySecret instead';
    expect(errorsOf(new ApiError(400, credential, 'invalid_federated_source'))).toEqual([credential]);
    expect(errorsOf(new ApiError(403, 'x; y', 'superuser_required'))).toEqual(['x; y']);
    expect(errorsOf(new Error('net'))).toEqual(['Error: net']);
  });
});
