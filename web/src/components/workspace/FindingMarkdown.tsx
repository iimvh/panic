import type { ReactNode } from "react";

const INLINE_MARKER = /(\*\*[\s\S]+?\*\*|`[^`\n]+`)/g;
const LIST_MARKER = /^\s*(?:[-*]\s+|\d+\.\s+)/;
const ORDERED_MARKER = /^\s*\d+\.\s+/;
const HEADING_MARKER = /^(#{1,3})\s+(.*)$/;

function isBold(part: string): boolean {
	return /^\*\*[\s\S]+\*\*$/.test(part) && part.length > 4;
}

function isCodeChip(part: string): boolean {
	return /^`[^`\n]+`$/.test(part) && part.length > 2;
}

function inlineNodes(text: string, keyPrefix: string): ReactNode[] {
	return text.split(INLINE_MARKER).flatMap((part, index): ReactNode[] => {
		const key = `${keyPrefix}:${index}`;
		if (part === "") return [];
		if (isBold(part)) {
			return [
				<strong key={key} className="font-semibold text-fg">
					{part.slice(2, -2)}
				</strong>,
			];
		}
		if (isCodeChip(part)) {
			return [
				<code
					key={key}
					className="border border-edge bg-edge-soft px-1 font-mono text-[0.85em]"
				>
					{part.slice(1, -1)}
				</code>,
			];
		}
		return [part];
	});
}

/** One source line lifted into a list item (marker stripped, inline markup kept). */
function listItemText(line: string): string {
	return line.replace(/^\s*(?:[-*]\s+|\d+\.\s+)/, "");
}

/**
 * Renders one source block: heading lines, consecutive marker lines become one
 * list, and consecutive prose lines collapse into a paragraph (markdown soft-wrap).
 */
function renderLines(lines: string[], keyPrefix: string): ReactNode[] {
	const nodes: ReactNode[] = [];
	let list: { ordered: boolean; items: string[] } | null = null;
	let paragraph: string[] = [];

	const flushParagraph = () => {
		if (paragraph.length === 0) return;
		nodes.push(
			<p key={`${keyPrefix}p${nodes.length}`}>{inlineNodes(paragraph.join(" "), `${keyPrefix}i${nodes.length}`)}</p>
		);
		paragraph = [];
	};
	const flushList = () => {
		if (!list) return;
		nodes.push(
			<ol
				key={`${keyPrefix}l${nodes.length}`}
				className={`${list.ordered ? "list-decimal" : "list-disc"} space-y-1 pl-5 marker:text-fg-faint`}
			>
				{list.items.map((item, index) => (
					<li key={index}>{inlineNodes(item, `${keyPrefix}li${index}`)}</li>
				))}
			</ol>
		);
		list = null;
	};

	lines.forEach((line, edgeLine) => {
		const heading = line.match(HEADING_MARKER);
		if (heading) {
			flushParagraph();
			flushList();
			nodes.push(
				<p
					key={`${keyPrefix}h${edgeLine}`}
					className="font-semibold text-fg"
				>
					{inlineNodes(heading[2], `${keyPrefix}hi${edgeLine}`)}
				</p>
			);
			return;
		}
		if (LIST_MARKER.test(line)) {
			flushParagraph();
			const ordered = ORDERED_MARKER.test(line);
			if (!list || list.ordered !== ordered) {
				flushList();
				list = { ordered, items: [] };
			}
			list.items.push(listItemText(line));
			return;
		}
		flushList();
		if (line.trim() === "") return;
		paragraph.push(line.trim());
	});
	flushParagraph();
	flushList();
	return nodes;
}

/**
 * The executive finding's markdown: bold, inline code chips, ordered/unordered
 * lists and light headings — enough for streaming agent answers, no dependencies.
 * Markers left unclosed mid-stream render as literal text until they complete.
 */
export default function FindingMarkdown({ text }: { text: string }) {
	const blocks = text.split(/\n\n+/).filter((block) => block.trim() !== "");
	return (
		<>
			{blocks.map((block, index) => (
				<div key={index} className="space-y-2">
					{renderLines(block.split("\n"), String(index))}
				</div>
			))}
		</>
	);
}
