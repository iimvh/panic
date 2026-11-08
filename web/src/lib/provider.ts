const PROVIDER_ENDPOINT = "/api/provider";

/** Connection status as GET /api/provider answers it; absent ids mean "not connected yet". */
export interface ProviderStatus {
	provider: string | null;
	model: string | null;
	connected: boolean;
	keyHint: string | null;
}

/** One composer-selectable model out of the provider's free list. */
export interface FreeLlmModel {
	id: string;
	name: string;
	contextTokens: number;
}

export interface ProviderTestResult {
	ok: boolean;
	detail: string;
}

export async function fetchProviderStatus(): Promise<ProviderStatus> {
	const response = await fetch(PROVIDER_ENDPOINT, { headers: { Accept: "application/json" } });
	if (!response.ok) throw new Error(`Provider status ${response.status}`);
	return parseStatus(await response.json());
}

/** Connects (or reconfigures) a provider; a key the server rejects throws with its message. */
export async function connectProvider(
	provider: string,
	apiKey: string,
	model: string
): Promise<ProviderStatus> {
	const response = await fetch(PROVIDER_ENDPOINT, {
		method: "PUT",
		headers: { "Content-Type": "application/json", Accept: "application/json" },
		body: JSON.stringify({ provider, apiKey, model }),
	});
	const json = await response.json().catch(() => ({}));
	if (!response.ok) throw new Error(typeof json.error === "string" ? json.error : `Connect failed (${response.status})`);
	return parseStatus(json);
}

/** Free models of the connected provider surface; a failed fetch answers an empty list. */
export async function fetchFreeModels(): Promise<FreeLlmModel[]> {
	try {
		const response = await fetch(`${PROVIDER_ENDPOINT}/models`, { headers: { Accept: "application/json" } });
		if (!response.ok) return [];
		const rows = await response.json();
		if (!Array.isArray(rows)) return [];
		return rows
			.filter((row): row is Record<string, unknown> => typeof row === "object" && row !== null)
			.map((row) => ({
				id: String(row.id ?? ""),
				name: String(row.name ?? row.id ?? ""),
				contextTokens: typeof row.contextTokens === "number" ? row.contextTokens : 0,
			}))
			.filter((model) => model.id);
	} catch {
		return [];
	}
}

export async function testProviderKey(apiKey: string): Promise<ProviderTestResult> {
	const response = await fetch(`${PROVIDER_ENDPOINT}/test`, {
		method: "POST",
		headers: { "Content-Type": "application/json", Accept: "application/json" },
		body: JSON.stringify({ apiKey }),
	});
	const json = await response.json().catch(() => ({}));
	return {
		ok: json.ok === true,
		detail: typeof json.detail === "string" ? json.detail : "Probe failed",
	};
}

function parseStatus(json: unknown): ProviderStatus {
	const row = (typeof json === "object" && json !== null ? json : {}) as Record<string, unknown>;
	return {
		provider: typeof row.provider === "string" ? row.provider : null,
		model: typeof row.model === "string" ? row.model : null,
		connected: row.connected === true,
		keyHint: typeof row.keyHint === "string" ? row.keyHint : null,
	};
}
