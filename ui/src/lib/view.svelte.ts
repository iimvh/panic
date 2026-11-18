import { chat, type Message } from './chat.svelte';
import { flatten, type Mode } from './scene';

/**
 * How the agent map is shown beside the chat: its size (side by side, the map in focus, or a strip above the
 * message box), the tab of its panel, and the step being replayed. Page state only; size and width are kept in this
 * browser between visits.
 */
export type MapSize = 'side' | 'focus' | 'mini';
export type PaneTab = 'map' | 'step' | 'trace';

const SIZES: MapSize[] = ['side', 'focus', 'mini'];
const SIZE_KEY = 'panic.map.size';
const WIDTH_KEY = 'panic.map.width';

class MapView {
	size = $state<MapSize>(load(SIZE_KEY, (v) => (SIZES as string[]).includes(v), 'side') as MapSize);
	/** The panel's share of the window in side-by-side mode (0.3 to 0.7). */
	width = $state(Number(load(WIDTH_KEY, (v) => Number(v) >= 0.3 && Number(v) <= 0.7, '0.5')));
	tab = $state<PaneTab>('map');
	/** Narrow screens: the map is a sheet over the chat, open or not. */
	sheet = $state(false);
	/** The step replayed (R, ← →); undefined follows the answer (or the step picked in the chat). */
	replay = $state<number | undefined>(undefined);
	playing = $state(false);

	setSize(size: MapSize) {
		this.size = size;
		save(SIZE_KEY, size);
	}

	/** Side → focus → mini → side (the M key). */
	cycle() {
		this.setSize(SIZES[(SIZES.indexOf(this.size) + 1) % SIZES.length]);
	}

	setWidth(share: number) {
		this.width = Math.min(0.7, Math.max(0.3, share));
		save(WIDTH_KEY, String(this.width));
	}

	/** The answer the map draws: the one of the call or trace picked in the chat, else the latest. */
	get subject(): Message | undefined {
		const picked = chat.inspected;
		if (picked?.kind === 'tool') return chat.messages.find((m) => m.id === picked.messageId);
		if (picked?.kind === 'trace') return chat.messages.find((m) => m.traceId === picked.traceId);
		return chat.messages.findLast((m) => m.role === 'assistant');
	}

	/** The step the map shows: the one replayed, else the call picked in the chat; undefined is live. */
	get atStep(): number | undefined {
		if (this.replay !== undefined) return this.replay;
		const picked = chat.inspected;
		const subject = this.subject;
		if (picked?.kind !== 'tool' || !subject) return undefined;
		const i = flatten(subject.parts).findIndex((f) => f.step.id === picked.id);
		return i >= 0 ? i : undefined;
	}

	get stepCount(): number {
		return flatten(this.subject?.parts).length;
	}

	get mode(): Mode {
		return chat.mode?.plan ? 'plan' : chat.permissions?.bypass ? 'auto' : 'ask';
	}

	/** Set while the rail picks a call, so the panel keeps its tab (a pick in the chat opens the Step tab). */
	fromRail = false;

	/** Picks step `i` on the rail: the map shows it and the Step tab holds its detail, without switching to it. */
	pick(i: number) {
		const subject = this.subject;
		const f = flatten(subject?.parts)[i];
		if (!subject || !f) return;
		this.show(i);
		this.fromRail = true;
		chat.inspected = { kind: 'tool', messageId: subject.id, id: f.step.id, agent: f.agent };
	}

	/** Shows step `i` of the answer on the map. */
	show(i: number) {
		const n = this.stepCount;
		if (n === 0) return;
		this.playing = false;
		this.replay = Math.max(0, Math.min(n - 1, i));
	}

	step(by: number) {
		const at = this.atStep ?? (by > 0 ? -1 : this.stepCount);
		this.pick(Math.max(0, Math.min(this.stepCount - 1, at + by)));
	}

	/** Back to what happens now. */
	live() {
		this.playing = false;
		this.replay = undefined;
		if (chat.inspected?.kind === 'tool') chat.inspected = null;
	}

	/** Plays the answer's steps from the start (R), or pauses and resumes it (Space). */
	play(fromStart = false) {
		if (this.stepCount === 0) return;
		if (fromStart || this.replay === undefined || this.replay >= this.stepCount - 1) this.replay = 0;
		this.playing = true;
	}

	toggle() {
		if (this.playing) this.playing = false;
		else this.play();
	}

	/** One tick of playing: the next step, stopping at the last. */
	advance() {
		if (!this.playing || this.replay === undefined) return;
		if (this.replay >= this.stepCount - 1) this.playing = false;
		else this.replay++;
	}
}

function load(key: string, ok: (v: string) => boolean, fallback: string): string {
	try {
		const v = localStorage.getItem(key);
		return v !== null && ok(v) ? v : fallback;
	} catch {
		return fallback;
	}
}

function save(key: string, value: string) {
	try {
		localStorage.setItem(key, value);
	} catch {
		// private window or blocked storage: the choice lasts until the page closes
	}
}

export const view = new MapView();
