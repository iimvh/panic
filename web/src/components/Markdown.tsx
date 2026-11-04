import { Fragment, type ReactNode } from "react";

interface Block {
	kind: "p" | "h" | "ul" | "ol" | "gap" | "pre";
	items?: string[];
	text?: string;
	lang?: string;
}

function renderInline(value: string, keyPrefix: string): ReactNode[] {
	const nodes: ReactNode[] = [];
	const pattern = /(`[^`]+`|\*\*[^*]+\*\*)/g;
	let lastIndex = 0;
	let match: RegExpExecArray | null;

	while ((match = pattern.exec(value)) !== null) {
		if (match.index > lastIndex) nodes.push(value.slice(lastIndex, match.index));
		const token = match[0];
		if (token.startsWith("`")) {
			nodes.push(
				<code
					key={`${keyPrefix}-c${match.index}`}
					className="rounded-xs border border-hairline bg-surface-alt px-1.5 py-0.5 font-mono text-[0.86em] text-body-muted"
				>
					{token.slice(1, -1)}
				</code>
			);
		} else {
			nodes.push(
				<strong key={`${keyPrefix}-b${match.index}`} className="font-sans font-semibold text-ink">
					{token.slice(2, -2)}
				</strong>
			);
		}
		lastIndex = pattern.lastIndex;
	}

	if (lastIndex < value.length) nodes.push(value.slice(lastIndex));
	return nodes;
}

function parseBlocks(markdown: string): Block[] {
	const blocks: Block[] = [];
	const parts = markdown.split("```");

	parts.forEach((part, index) => {
		if (index % 2 === 1) {
			const newline = part.indexOf("\n");
			const lang = newline >= 0 ? part.slice(0, newline).trim() : "";
			const code = newline >= 0 ? part.slice(newline + 1) : part;
			blocks.push({ kind: "pre", text: code.replace(/\s+$/, ""), lang });
			return;
		}

		let list: Block | null = null;
		for (const rawLine of part.split("\n")) {
			const line = rawLine.replace(/\s+$/, "");
			const unordered = line.match(/^\s*[-*•]\s+(.*)$/);
			const ordered = line.match(/^\s*\d+[.)]\s+(.*)$/);

			if (unordered || ordered) {
				const kind = unordered ? "ul" : "ol";
				if (!list || list.kind !== kind) {
					list = { kind, items: [] };
					blocks.push(list);
				}
				list.items!.push((unordered ?? ordered)![1]);
				continue;
			}

			list = null;

			if (!line.trim()) {
				blocks.push({ kind: "gap" });
				continue;
			}

			const heading = line.match(/^\s*#{1,6}\s+(.*)$/);
			blocks.push({ kind: heading ? "h" : "p", text: heading ? heading[1] : line });
		}
	});

	return blocks;
}

export default function Markdown({ text }: { text: string }) {
	const blocks = parseBlocks(text);

	return (
		<div className="font-sans text-[15px] leading-[1.5]">
			{blocks.map((block, index) => {
				const key = `b${index}`;
				switch (block.kind) {
					case "gap":
						return <div key={key} className="h-2" />;
					case "h":
						return (
							<p key={key} className="mb-1 mt-3 font-sans text-[14px] font-semibold text-ink first:mt-0">
								{renderInline(block.text!, key)}
							</p>
						);
					case "ul":
					case "ol":
						return (
							<ul
								key={key}
								className={
									block.kind === "ul"
										? "my-2 list-disc space-y-1 pl-5 marker:text-muted-soft"
										: "my-2 list-decimal space-y-1 pl-5 marker:font-sans marker:text-muted"
								}
							>
								{block.items!.map((item, itemIndex) => (
									<li key={`${key}-${itemIndex}`}>{renderInline(item, `${key}-${itemIndex}`)}</li>
								))}
							</ul>
						);
					case "pre":
						return (
							<Fragment key={key}>
								<pre className="scrollbar-thin my-2 overflow-x-auto rounded-sm border border-hairline bg-canvas p-3.5 font-mono text-[12.5px] leading-relaxed text-ink-soft">
									<code>{block.text}</code>
								</pre>
							</Fragment>
						);
					default:
						return (
							<p key={key} className="mb-1.5 last:mb-0">
								{renderInline(block.text!, key)}
							</p>
						);
				}
			})}
		</div>
	);
}
