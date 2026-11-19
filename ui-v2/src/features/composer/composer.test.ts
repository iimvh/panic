import { fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { chat } from '$lib/state/chat.svelte';
import Composer from './Composer.svelte';

afterEach(() => {
	vi.restoreAllMocks();
	chat.state = null;
	chat.busy = false;
});

describe('Composer', () => {
	it('sends on Enter and keeps Shift+Enter for a new line', async () => {
		const send = vi.spyOn(chat, 'send').mockResolvedValue();
		render(Composer);
		const box = screen.getByLabelText('Message') as HTMLTextAreaElement;
		await fireEvent.input(box, { target: { value: 'hello' } });
		await fireEvent.keyDown(box, { key: 'Enter', shiftKey: true });
		expect(send).not.toHaveBeenCalled();
		await fireEvent.keyDown(box, { key: 'Enter' });
		expect(send).toHaveBeenCalledWith('hello');
		expect(box.value).toBe('');
	});

	it('suggests the commands available now and completes with Tab', async () => {
		chat.state = {
			provider: 'p',
			model: 'm',
			reasoningEffort: null,
			connected: true,
			effortSupported: null,
			providers: [],
			commands: [
				{ name: 'models', usage: 'models [filter]', description: 'List models' },
				{ name: 'mode', usage: 'mode', description: 'Show the mode' },
				{ name: 'help', usage: 'help', description: 'Help' }
			]
		};
		render(Composer);
		const box = screen.getByLabelText('Message') as HTMLTextAreaElement;
		await fireEvent.input(box, { target: { value: '/mo' } });
		const options = screen.getAllByRole('option');
		expect(options.map((o) => o.textContent)).toEqual([expect.stringContaining('/models'), expect.stringContaining('/mode')]);
		expect(box.getAttribute('aria-activedescendant')).toBe('cmd-models');
		await fireEvent.keyDown(box, { key: 'ArrowDown' });
		expect(box.getAttribute('aria-activedescendant')).toBe('cmd-mode');
		await fireEvent.keyDown(box, { key: 'Tab' });
		expect(box.value).toBe('/mode ');
		expect(screen.queryByRole('listbox')).toBeNull();
	});

	it('turns Send into Stop while an answer runs', async () => {
		const stop = vi.spyOn(chat, 'stop').mockImplementation(() => {});
		chat.busy = true;
		render(Composer);
		expect(screen.queryByRole('button', { name: 'Send' })).toBeNull();
		await fireEvent.click(screen.getByRole('button', { name: 'Stop' }));
		expect(stop).toHaveBeenCalled();
	});
});
