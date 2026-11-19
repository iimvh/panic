import { describe, expect, it } from 'vitest';
import { inline, renderMarkdown } from './markdown';

describe('renderMarkdown', () => {
	it('escapes HTML everywhere', () => {
		const html = renderMarkdown('<img src=x onerror=alert(1)> **<b>**\n\n```\n<script>\n```');
		expect(html).not.toContain('<img');
		expect(html).not.toContain('<script>');
		expect(html).toContain('&lt;img');
		expect(html).toContain('<strong>&lt;b&gt;</strong>');
	});

	it('keeps only safe links', () => {
		expect(inline('[ok](https://a.b/c) [bad](javascript:alert(1))')).toBe(
			'<a href="https://a.b/c" target="_blank" rel="noopener noreferrer">ok</a> [bad](javascript:alert(1))'
		);
	});

	it('leaves markup inside inline code alone', () => {
		expect(inline('use `**x**` and **y**')).toBe('use <code>**x**</code> and <strong>y</strong>');
	});

	it('renders blocks', () => {
		const html = renderMarkdown('# Title\n\n- a\n- b\n\n1. one\n2. two\n\n> quote\n\ntext\nmore');
		expect(html).toBe(
			'<h1>Title</h1><ul><li>a</li><li>b</li></ul><ol><li>one</li><li>two</li></ol>' +
				'<blockquote><p>quote</p></blockquote><p>text<br>more</p>'
		);
	});

	it('keeps one numbered list when blank lines separate its items', () => {
		expect(renderMarkdown('1. one\n\n2. two\n\n3. three')).toBe('<ol><li>one</li><li>two</li><li>three</li></ol>');
		expect(renderMarkdown('1. one\n\n   more about one\n\n2. two')).toBe(
			'<ol><li>one more about one</li><li>two</li></ol>'
		);
		expect(renderMarkdown('- a\n\n- b\n\ntext')).toBe('<ul><li>a</li><li>b</li></ul><p>text</p>');
	});

	it('keeps the number a list starts at', () => {
		expect(renderMarkdown('3. three\n4. four')).toBe('<ol start="3"><li>three</li><li>four</li></ol>');
		expect(renderMarkdown('1. one\n\ntext\n\n2. two')).toBe(
			'<ol><li>one</li></ol><p>text</p><ol start="2"><li>two</li></ol>'
		);
	});

	it('renders fenced code, also while still streaming', () => {
		expect(renderMarkdown('```js\nlet a = 1;\n```')).toContain('<code class="language-js">let a = 1;</code>');
		expect(renderMarkdown('see:\n```py\nprint(1)')).toContain('<code class="language-py">print(1)</code>');
	});

	it('renders tables', () => {
		const html = renderMarkdown('| a | b |\n|---|---|\n| 1 | **2** |');
		expect(html).toBe(
			'<table><thead><tr><th>a</th><th>b</th></tr></thead><tbody><tr><td>1</td><td><strong>2</strong></td></tr></tbody></table>'
		);
	});
});
