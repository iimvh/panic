import { useEffect, useRef, useState } from "react";
import { fetchFreeModels, testProviderKey, type FreeLlmModel, type ProviderStatus } from "../../lib/provider";

interface SettingsPanelProps {
	status: ProviderStatus | null;
	onClose: () => void;
	/** Persists the connection; rejects with the server's message when the key fails. */
	onConnect: (provider: string, apiKey: string, model: string) => Promise<void>;
}

/**
 * Small floating "Connect provider" popover anchored above the sidebar footer:
 * one provider card (OpenRouter), the key field and the free-model picker. Keys
 * live server-side; the panel only forwards them through /api/provider.
 */
export default function SettingsPanel({ status, onClose, onConnect }: SettingsPanelProps) {
	const [apiKey, setApiKey] = useState("");
	const [model, setModel] = useState(status?.model ?? "");
	const [models, setModels] = useState<FreeLlmModel[]>([]);
	const [probe, setProbe] = useState<{ ok: boolean; detail: string } | null>(null);
	const [saving, setSaving] = useState(false);
	const [error, setError] = useState<string | null>(null);
	const panelRef = useRef<HTMLDivElement>(null);

	// Free-model catalogue on open; defaults to the currently connected model.
	useEffect(() => {
		let alive = true;
		fetchFreeModels().then((rows) => {
			if (!alive) return;
			setModels(rows);
			setModel((current) => current || rows[0]?.id || "");
		});
		return () => {
			alive = false;
		};
	}, []);

	// Escape folds the panel; a click outside closes it too.
	useEffect(() => {
		const onKey = (event: globalThis.KeyboardEvent) => {
			if (event.key === "Escape") onClose();
		};
		const onClick = (event: MouseEvent) => {
			if (panelRef.current && !panelRef.current.contains(event.target as Node)) onClose();
		};
		document.addEventListener("keydown", onKey);
		document.addEventListener("mousedown", onClick);
		return () => {
			document.removeEventListener("keydown", onKey);
			document.removeEventListener("mousedown", onClick);
		};
	}, [onClose]);

	const connected = status?.connected === true;

	const save = async () => {
		if (!model) {
			setError("Pick a model first.");
			return;
		}
		if (!connected && apiKey.trim() === "") {
			setError("Enter your OpenRouter API key to connect.");
			return;
		}
		setSaving(true);
		setError(null);
		try {
			await onConnect("openrouter", apiKey.trim(), model);
			setApiKey("");
		} finally {
			setSaving(false);
		}
	};

	const runProbe = async () => {
		setProbe(null);
		setError(null);
		try {
			setProbe(await testProviderKey(apiKey.trim()));
		} catch (probeError) {
			setProbe({ ok: false, detail: probeError instanceof Error ? probeError.message : "Probe failed" });
		}
	};

	return (
		<div
			ref={panelRef}
			role="dialog"
			aria-label="Settings"
			className="fixed bottom-14 left-4 z-50 w-80 border border-edge bg-surface-raised shadow-lg"
		>
			<div className="flex items-center justify-between border-b border-edge px-4 py-2.5">
				<span className="micro-label text-fg-faint">Settings</span>
				<button
					type="button"
					onClick={onClose}
					aria-label="Close settings"
					className="cursor-pointer font-mono text-[11px] text-fg-faint transition-colors hover:text-fg"
				>
					✕
				</button>
			</div>

			<div className="p-4">
				<div className="micro-label text-fg-faint">Connect provider</div>

				<div className="mt-2 flex items-center justify-between border border-edge bg-canvas px-3 py-2">
					<span className="font-mono text-[11px] font-semibold tracking-tight text-fg">OPENROUTER</span>
					<span
						className={`font-mono text-[10px] uppercase tracking-wider ${
							connected ? "text-emerald-600 dark:text-emerald-400" : "text-fg-faint"
						}`}
					>
						{connected ? "● Connected" : "○ Not connected"}
					</span>
				</div>

				<label className="mt-3 block font-mono text-[10px] uppercase tracking-wider text-fg-faint" htmlFor="provider-key">
					API key
				</label>
				<input
					id="provider-key"
					type="password"
					value={apiKey}
					onChange={(event) => setApiKey(event.target.value)}
					placeholder={connected && status?.keyHint ? `Stored key · ${status.keyHint}` : "sk-or-v1-..."}
					className="mt-1 w-full border border-edge bg-canvas px-3 py-1.5 font-mono text-xs text-fg placeholder:text-fg-faint focus:border-accent focus:outline-none"
				/>

				<label className="mt-3 block font-mono text-[10px] uppercase tracking-wider text-fg-faint" htmlFor="provider-model">
					Model (free)
				</label>
				<select
					id="provider-model"
					value={model}
					onChange={(event) => setModel(event.target.value)}
					className="mt-1 w-full cursor-pointer border border-edge bg-canvas px-3 py-1.5 font-mono text-xs text-fg focus:border-accent focus:outline-none"
				>
					{models.length === 0 ? <option value="">Loading free models…</option> : null}
					{models.map((entry) => (
						<option key={entry.id} value={entry.id}>
							{entry.name} · {Math.round(entry.contextTokens / 1024)}K
						</option>
					))}
				</select>

				{probe ? (
					<div
						className={`mt-3 border px-3 py-2 font-mono text-[10px] leading-relaxed ${
							probe.ok ? "border-emerald-600/40 text-emerald-600 dark:text-emerald-400" : "border-red-600/40 text-red-600"
						}`}
					>
						{probe.ok ? "✓" : "✕"} {probe.detail}
					</div>
				) : null}

				{error ? (
					<div className="mt-3 border border-red-600/40 px-3 py-2 font-mono text-[10px] leading-relaxed text-red-600">
						{error}
					</div>
				) : null}

				<div className="mt-4 flex items-center justify-between">
					<button
						type="button"
						onClick={() => void runProbe()}
						className="cursor-pointer font-mono text-[10px] uppercase tracking-wider text-fg-faint transition-colors hover:text-fg"
					>
						Test connection
					</button>
					<button
						type="button"
						onClick={() => void save()}
						disabled={saving}
						className={`cursor-pointer px-4 py-1.5 font-mono text-[10px] uppercase tracking-wider transition-colors ${
							saving ? "bg-edge text-fg-faint" : "bg-fg text-canvas hover:bg-accent hover:text-white"
						}`}
					>
						{saving ? "Saving…" : "Save"}
					</button>
				</div>
			</div>
		</div>
	);
}
