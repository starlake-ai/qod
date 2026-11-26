import { ApiError, errorMessage } from '../api/client';
import type {
  FederatedSecretListResponse, FederatedSecretResponse, FederatedSecretUpsertRequest,
  FederatedSourceCreateRequest, FederatedSourceResponse,
} from '../api/types';
import type { SaveResult, SourceEditorSecret } from './sourceEditorContract';

/** The federation calls a save makes; the console passes `api` from ../api/client. */
export interface PluginSaveApi {
  createFederatedSource(tenant: string, tenantDb: string, req: FederatedSourceCreateRequest): Promise<FederatedSourceResponse>;
  listFederatedSecrets(tenant: string, tenantDb: string, alias: string): Promise<FederatedSecretListResponse>;
  upsertFederatedSecret(tenant: string, tenantDb: string, alias: string, req: FederatedSecretUpsertRequest): Promise<FederatedSecretResponse>;
  deleteFederatedSecret(tenant: string, tenantDb: string, alias: string, name: string): Promise<void>;
}

export interface PluginSaveInput {
  tenant: string;
  tenantDb: string;
  /** As typed in core's form; normalized here as the manager stores it. */
  alias: string;
  description: string;
  sourceType: string;
  pluginConfig: unknown;
  secrets: SourceEditorSecret[];
}

/** ctx.save: the source `{alias, description, sourceType, readOnly: true, pluginConfig}`, then each
 *  secret, through the sequence the form uses for a Postgres password, for a list of secrets:
 *  - a new source (its secret listing 404s) is saved disabled, then every secret, then as asked:
 *    an enabled source naming a missing secret would fail the tenant-db's federation at spawn;
 *  - an existing source holding no secrets takes the same path;
 *  - an existing source holding some is saved first and its secrets after, so a refusal writes nothing; secrets
 *    new to a source already holding others go first, so their placeholders resolve at every
 *    instant, and are deleted again on a refusal;
 *  - a secret held through an externalRef is never overwritten with a value.
 *  Never throws: every failure comes back as `{ok: false, errors}`. */
export async function savePluginSource(api: PluginSaveApi, input: PluginSaveInput): Promise<SaveResult> {
  const { tenant, tenantDb } = input;
  const alias = input.alias.trim().toLowerCase();
  if (alias === '') return { ok: false, errors: ['alias is required'] };
  const request: FederatedSourceCreateRequest = {
    alias,
    description: input.description.trim() || undefined,
    sourceType: input.sourceType,
    readOnly: true,
    pluginConfig: input.pluginConfig,
  };
  const targets = input.secrets;
  try {
    if (targets.length === 0) {
      await api.createFederatedSource(tenant, tenantDb, request);
      return { ok: true };
    }
    const stored = await api.listFederatedSecrets(tenant, tenantDb, alias)
      .then(r => r.secrets)
      .catch(e => {
        if (e instanceof ApiError && e.status === 404) return null;
        throw e;
      });
    if (stored == null || stored.length === 0) return await saveDisabledFirst(api, input, alias, request);
    for (const s of targets) {
      const ref = stored.find(x => x.name === s.name)?.externalRef;
      if (ref)
        return {
          ok: false,
          errors: [`secret ${s.name} is resolved from ${ref}; change it under Secrets instead of saving a value here`],
        };
    }
    const fresh = targets.filter(s => !stored.some(x => x.name === s.name));
    const held = targets.filter(s => stored.some(x => x.name === s.name));
    const written: string[] = [];
    try {
      for (const s of fresh) {
        await api.upsertFederatedSecret(tenant, tenantDb, alias, s);
        written.push(s.name);
      }
      await api.createFederatedSource(tenant, tenantDb, request);
    } catch (e) {
      const stuck: string[] = [];
      for (const name of written) {
        try {
          await api.deleteFederatedSecret(tenant, tenantDb, alias, name);
        } catch {
          stuck.push(name);
        }
      }
      return {
        ok: false,
        errors: [errorsOf(e)[0] + stuck.map(n => `; secret ${n} could not be removed`).join('')],
      };
    }
    for (const s of held) {
      try {
        await api.upsertFederatedSecret(tenant, tenantDb, alias, s);
      } catch (e) {
        return {
          ok: false,
          errors: [`the source configuration was saved but secret ${s.name} was not updated (${errorMessage(e)})`],
        };
      }
    }
    return { ok: true };
  } catch (e) {
    return { ok: false, errors: errorsOf(e) };
  }
}

/** A source with no stored secrets (new, or existing and holding none): saved disabled, then every
 *  secret, then enabled, so an enabled source never names a missing secret. */
async function saveDisabledFirst(
  api: PluginSaveApi, input: PluginSaveInput, alias: string, request: FederatedSourceCreateRequest,
): Promise<SaveResult> {
  const { tenant, tenantDb } = input;
  await api.createFederatedSource(tenant, tenantDb, { ...request, disabled: true });
  for (const s of input.secrets) {
    try {
      await api.upsertFederatedSecret(tenant, tenantDb, alias, s);
    } catch (e) {
      return {
        ok: false,
        errors: [`the source was saved DISABLED but its secret ${s.name} was not (${errorMessage(e)}); ` +
          `expand the source, set secret ${s.name} under Secrets, then save the source again to enable it`],
      };
    }
  }
  try {
    await api.createFederatedSource(tenant, tenantDb, request);
  } catch (e) {
    return { ok: false, errors: [`${errorsOf(e)[0]}; the source remains disabled`] };
  }
  return { ok: true };
}

/** One message per failure. The manager joins a refused source's errors with "; "
 *  (FederatedSourceHandlers.toSource), but a provider error may itself contain "; ", so the message
 *  is never split; an editor that wants per-field errors validates through its own module first. */
export function errorsOf(e: unknown): string[] {
  if (e instanceof ApiError && e.status === 400 && e.code === 'invalid_federated_source')
    return [e.message];
  return [errorMessage(e)];
}
