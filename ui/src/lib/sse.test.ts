import { describe, expect, it } from 'vitest';
import { createSseParser, type SseEvent } from './sse';

describe('createSseParser', () => {
	it('handles events split across chunks, comments and CRLF', () => {
		const got: SseEvent[] = [];
		const p = createSseParser((e) => got.push(e));
		p.feed('event: delta\ndata: {"te');
		p.feed('xt":"Hel"}\n\n: ping\n\nevent: delta\r\ndata: {"text":"lo"}\r\n\r\n');
		p.feed('event: done\ndata: {}');
		p.end();
		expect(got).toEqual([
			{ event: 'delta', data: { text: 'Hel' } },
			{ event: 'delta', data: { text: 'lo' } },
			{ event: 'done', data: {} }
		]);
	});

	it('keeps non-JSON data as text', () => {
		const got: SseEvent[] = [];
		const p = createSseParser((e) => got.push(e));
		p.feed('data: hello\ndata: world\n\n');
		expect(got).toEqual([{ event: 'message', data: 'hello\nworld' }]);
	});
});
