/**
 * A small, dependency-free Markdown renderer for chat answers. Everything is HTML-escaped first, so the
 * output is safe to inject; only http(s)/mailto links are kept. An unclosed ``` fence (while an answer is
 * still streaming) renders as code up to the end.
 *
 * Supported: fenced code, inline code, headings, bold, italic, strikethrough, links, bullet and numbered
 * lists, blockquotes, horizontal rules, tables, paragraphs.
 */

const ESC: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };

export function escapeHtml(s: string): string {
	return s.replace(/[&<>"']/g, (c) => ESC[c]);
}

function safeUrl(url: string): string | null {
	const u = url.trim();
	return /^(https?:\/\/|mailto:)/i.test(u) ? u : null;
}

/** Inline markup on one line of raw text. */
export function inline(raw: string): string {
	const codes: string[] = [];
	// protect code spans before anything else touches them
	let s = raw.replace(/`([^`\n]+)`/g, (_, code: string) => {
		codes.push(`<code>${escapeHtml(code)}</code>`);
		return `\u0000${codes.length - 1}\u0000`;
	});
	const links: string[] = [];
	s = s.replace(/\[([^\]\n]+)\]\(([^)\s]+)\)/g, (m, text: string, url: string) => {
		const href = safeUrl(url);
		if (!href) return m;
		links.push(
			`<a href="${escapeHtml(href)}" target="_blank" rel="noopener noreferrer">${escapeHtml(text)}</a>`
		);
		return `\u0001${links.length - 1}\u0001`;
	});
	s = escapeHtml(s)
		.replace(/\*\*(?=\S)([\s\S]*?\S)\*\*/g, '<strong>$1</strong>')
		.replace(/__(?=\S)([\s\S]*?\S)__/g, '<strong>$1</strong>')
		.replace(/(^|[^*\w])\*(?=\S)([^*]*?\S)\*(?!\*)/g, '$1<em>$2</em>')
		.replace(/(^|[^_\w])_(?=\S)([^_]*?\S)_(?!\w)/g, '$1<em>$2</em>')
		.replace(/~~(?=\S)([\s\S]*?\S)~~/g, '<del>$1</del>');
	return s
		.replace(/\u0001(\d+)\u0001/g, (_, i: string) => links[Number(i)])
		.replace(/\u0000(\d+)\u0000/g, (_, i: string) => codes[Number(i)]);
}

function codeBlock(lang: string, code: string): string {
	const cls = lang ? ` class="language-${escapeHtml(lang)}"` : '';
	const label = lang ? `<span class="lang">${escapeHtml(lang)}</span>` : '';
	return (
		`<div class="code">${label}<button type="button" class="copy" data-copy>Copy</button>` +
		`<pre><code${cls}>${escapeHtml(code)}</code></pre></div>`
	);
}

const LIST_ITEM = /^(\s*)([-*+]|\d+[.)])\s+(.*)$/;

function isTableRow(line: string): boolean {
	return /^\s*\|.*\|\s*$/.test(line);
}

function cells(line: string): string[] {
	return line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((c) => c.trim());
}

function blocks(text: string): string {
	const lines = text.split('\n');
	const out: string[] = [];
	let i = 0;
	while (i < lines.length) {
		const line = lines[i];
		if (line.trim() === '') {
			i++;
			continue;
		}
		const heading = /^(#{1,6})\s+(.*)$/.exec(line);
		if (heading) {
			const level = heading[1].length;
			out.push(`<h${level}>${inline(heading[2].replace(/\s+#+\s*$/, ''))}</h${level}>`);
			i++;
			continue;
		}
		if (/^\s*([-*_])(\s*\1){2,}\s*$/.test(line)) {
			out.push('<hr>');
			i++;
			continue;
		}
		if (/^\s*>/.test(line)) {
			const quoted: string[] = [];
			while (i < lines.length && /^\s*>/.test(lines[i])) quoted.push(lines[i++].replace(/^\s*>\s?/, ''));
			out.push(`<blockquote>${blocks(quoted.join('\n'))}</blockquote>`);
			continue;
		}
		if (isTableRow(line) && i + 1 < lines.length && /^\s*\|?\s*:?-{3,}/.test(lines[i + 1])) {
			const head = cells(line);
			i += 2;
			const rows: string[][] = [];
			while (i < lines.length && isTableRow(lines[i])) rows.push(cells(lines[i++]));
			out.push(
				'<table><thead><tr>' +
					head.map((h) => `<th>${inline(h)}</th>`).join('') +
					'</tr></thead><tbody>' +
					rows.map((r) => '<tr>' + r.map((c) => `<td>${inline(c)}</td>`).join('') + '</tr>').join('') +
					'</tbody></table>'
			);
			continue;
		}
		const item = LIST_ITEM.exec(line);
		if (item) {
			const ordered = /\d/.test(item[2]);
			const items: string[] = [];
			while (i < lines.length) {
				const m = LIST_ITEM.exec(lines[i]);
				if (m && /\d/.test(m[2]) === ordered) {
					items.push(m[3]);
					i++;
				} else if (lines[i].trim() !== '' && /^\s{2,}/.test(lines[i]) && items.length) {
					items[items.length - 1] += ' ' + lines[i++].trim(); // continuation line
				} else {
					break;
				}
			}
			const tag = ordered ? 'ol' : 'ul';
			out.push(`<${tag}>` + items.map((t) => `<li>${inline(t)}</li>`).join('') + `</${tag}>`);
			continue;
		}
		const para: string[] = [];
		while (
			i < lines.length &&
			lines[i].trim() !== '' &&
			!/^(#{1,6})\s/.test(lines[i]) &&
			!/^\s*>/.test(lines[i]) &&
			!LIST_ITEM.test(lines[i])
		) {
			para.push(lines[i++]);
		}
		out.push(`<p>${para.map(inline).join('<br>')}</p>`);
	}
	return out.join('');
}

export function renderMarkdown(src: string): string {
	const out: string[] = [];
	const fence = /^ {0,3}(```|~~~)\s*([\w+#.-]*)[^\n]*$/;
	const lines = src.replace(/\r\n?/g, '\n').split('\n');
	let text: string[] = [];
	let i = 0;
	while (i < lines.length) {
		const open = fence.exec(lines[i]);
		if (!open) {
			text.push(lines[i++]);
			continue;
		}
		out.push(blocks(text.join('\n')));
		text = [];
		const marker = open[1];
		const lang = open[2];
		const code: string[] = [];
		i++;
		while (i < lines.length && !new RegExp(`^ {0,3}${marker}\\s*$`).test(lines[i])) code.push(lines[i++]);
		i++; // closing fence (or end of a still-streaming answer)
		out.push(codeBlock(lang, code.join('\n')));
	}
	out.push(blocks(text.join('\n')));
	return out.join('');
}
