// The size budget of the built UI (run after `npm run build`): JS + CSS + HTML, gzipped, as a browser downloads it.
// v1 (with the agent map, no accessible primitives) was 88.8 KB. v2 pays for Bits UI's focus traps, dismissable
// layers and positioning (~35 KB); the budget keeps the rest from growing unnoticed. Raise it only with a reason.
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { gzipSync } from 'node:zlib';

const BUDGET_KB = 125;

function files(dir) {
	return readdirSync(dir).flatMap((f) => {
		const p = join(dir, f);
		return statSync(p).isDirectory() ? files(p) : [p];
	});
}

const sized = files('build')
	.filter((f) => /\.(js|css|html)$/.test(f))
	.map((f) => ({ f, gz: gzipSync(readFileSync(f), { level: 9 }).length }))
	.sort((a, b) => b.gz - a.gz);
const total = sized.reduce((n, x) => n + x.gz, 0) / 1000;
for (const x of sized.slice(0, 5)) console.log(`${(x.gz / 1000).toFixed(1).padStart(6)} KB  ${x.f}`);
console.log(`total ${total.toFixed(1)} KB gzip (budget ${BUDGET_KB} KB)`);
if (total > BUDGET_KB) {
	console.error(`over budget by ${(total - BUDGET_KB).toFixed(1)} KB`);
	process.exit(1);
}
