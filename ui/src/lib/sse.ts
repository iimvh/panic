/** One server-sent event; `data` is parsed JSON when it is JSON, else the raw text. */
export type SseEvent = { event: string; data: unknown };

/**
 * Incremental SSE parser: feed it text chunks as they arrive (they may split events anywhere).
 * Comments (`: ping`) are skipped.
 */
export function createSseParser(onEvent: (e: SseEvent) => void) {
	let buffer = '';

	function dispatch(block: string) {
		let event = 'message';
		const data: string[] = [];
		for (const line of block.split('\n')) {
			if (line === '' || line.startsWith(':')) continue;
			const colon = line.indexOf(':');
			const field = colon < 0 ? line : line.slice(0, colon);
			let value = colon < 0 ? '' : line.slice(colon + 1);
			if (value.startsWith(' ')) value = value.slice(1);
			if (field === 'event') event = value;
			else if (field === 'data') data.push(value);
		}
		if (data.length === 0 && event === 'message') return;
		const raw = data.join('\n');
		let parsed: unknown = raw;
		try {
			parsed = raw === '' ? {} : JSON.parse(raw);
		} catch {
			// not JSON: keep the text
		}
		onEvent({ event, data: parsed });
	}

	return {
		feed(chunk: string) {
			buffer += chunk.replace(/\r\n?/g, '\n');
			let end: number;
			while ((end = buffer.indexOf('\n\n')) >= 0) {
				const block = buffer.slice(0, end);
				buffer = buffer.slice(end + 2);
				dispatch(block);
			}
		},
		end() {
			if (buffer.trim() !== '') dispatch(buffer);
			buffer = '';
		}
	};
}
