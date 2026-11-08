import { useState } from "react";
import { Button } from "@/components/ui/button";
import {
	Dialog,
	DialogContent,
	DialogDescription,
	DialogFooter,
	DialogHeader,
	DialogTitle,
	DialogTrigger,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import {
	testProviderKey,
	type FreeLlmModel,
	type ProviderStatus,
} from "@/lib/provider";
import { cn } from "@/lib/utils";
import { SettingsIcon } from "lucide-react";

interface ProviderConnectDialogProps {
	status: ProviderStatus | null;
	models: FreeLlmModel[];
	/** Persists the connection; rejects with the server's message when the key fails. */
	onConnect: (provider: string, apiKey: string, model: string) => Promise<void>;
}

/**
 * The provider connection dialog: one provider card (OpenRouter), the key
 * field and the free-model picker. Keys live server-side; the dialog only
 * forwards them through /api/provider.
 */
export function ProviderConnectDialog({
	status,
	models,
	onConnect,
}: ProviderConnectDialogProps) {
	const [open, setOpen] = useState(false);
	const [apiKey, setApiKey] = useState("");
	const [model, setModel] = useState<string | "">(status?.model ?? "");
	const [probe, setProbe] = useState<{ ok: boolean; detail: string } | null>(
		null,
	);
	const [saving, setSaving] = useState(false);
	const [error, setError] = useState<string | null>(null);

	const connected = status?.connected === true;
	const effectiveModel = model || status?.model || models[0]?.id || "";

	const runProbe = async () => {
		setProbe(null);
		setError(null);
		try {
			setProbe(await testProviderKey(apiKey.trim()));
		} catch (probeError) {
			setProbe({
				ok: false,
				detail:
					probeError instanceof Error ? probeError.message : "Probe failed",
			});
		}
	};

	const save = async () => {
		if (!effectiveModel) {
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
			await onConnect("openrouter", apiKey.trim(), effectiveModel);
			setApiKey("");
			setProbe(null);
			setOpen(false);
		} catch (connectError) {
			setError(
				connectError instanceof Error
					? connectError.message
					: "The server could not connect the provider.",
			);
		} finally {
			setSaving(false);
		}
	};

	return (
		<Dialog open={open} onOpenChange={setOpen}>
			<DialogTrigger asChild>
				<Button
					variant="ghost"
					size="icon"
					className="size-7 shrink-0 text-muted-foreground"
					aria-label="Settings"
				>
					<SettingsIcon className="size-4" />
				</Button>
			</DialogTrigger>
			<DialogContent className="sm:max-w-md">
				<DialogHeader>
					<DialogTitle>Connect provider</DialogTitle>
					<DialogDescription>
						Keys live server-side. A failed key is reported before anything
						else changes.
					</DialogDescription>
				</DialogHeader>

				<div className="flex items-center justify-between rounded-md border bg-background px-3 py-2">
					<span className="font-mono text-xs font-semibold tracking-tight">
						OPENROUTER
					</span>
					<span
						className={cn(
							"font-mono text-[10px] uppercase tracking-wider text-muted-foreground",
							connected && "text-emerald-600 dark:text-emerald-400",
						)}
					>
						{connected ? "● Connected" : "○ Not connected"}
					</span>
				</div>

				<label
					className="mt-1 block font-mono text-[10px] uppercase tracking-wider text-muted-foreground"
					htmlFor="provider-key"
				>
					API key
				</label>
				<div className="flex items-center gap-2">
					<Input
						id="provider-key"
						type="password"
						placeholder={connected ? "Stored — leave blank to keep" : "sk-or-v1-..."}
						value={apiKey}
						onChange={(event) => setApiKey(event.target.value)}
						className="font-mono text-xs"
					/>
					<Button
						variant="outline"
						className="shrink-0 font-mono text-xs"
						disabled={apiKey.trim() === "" || saving}
						onClick={() => void runProbe()}
					>
						Probe
					</Button>
				</div>
				{probe ? (
					<p
						className={cn(
							"text-xs",
							probe.ok
								? "text-emerald-600 dark:text-emerald-400"
								: "text-destructive",
						)}
					>
						{probe.ok ? "✓ " : "✕ "}
						{probe.detail}
					</p>
				) : null}

				<label
					className="mt-1 block font-mono text-[10px] uppercase tracking-wider text-muted-foreground"
					htmlFor="provider-model"
				>
					Model
				</label>
				<select
					id="provider-model"
					value={effectiveModel}
					onChange={(event) => setModel(event.target.value)}
					className="border-input bg-background h-9 rounded-md border px-2 font-mono text-xs"
				>
					{effectiveModel && !models.some((row) => row.id === effectiveModel) ? (
						<option value={effectiveModel}>{effectiveModel}</option>
					) : null}
					{models.map((row) => (
						<option key={row.id} value={row.id}>
							{row.name}
						</option>
					))}
				</select>

				{error ? <p className="text-destructive text-xs">{error}</p> : null}

				<DialogFooter>
					<Button onClick={() => void save()} disabled={saving || !effectiveModel}>
						{saving ? "Connecting…" : "Save"}
					</Button>
				</DialogFooter>
			</DialogContent>
		</Dialog>
	);
}
