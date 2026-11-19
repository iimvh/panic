#!/usr/bin/env node
// Aggregates eval/results/<label>/*/result.json into a markdown table: one row per label and task, medians and
// ranges over the runs. Runs that ended on a provider error (infraError) are counted apart and left out.
//
//   node eval/report.mjs [label ...]      (default: every label under eval/results)
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const results = join(dirname(fileURLToPath(import.meta.url)), 'results');
const labels = process.argv.slice(2).length ? process.argv.slice(2) : readdirSync(results);

const median = (xs) => {
  if (!xs.length) return NaN;
  const s = [...xs].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
};
const fmt = (xs, scale = 1, digits = 0) => {
  if (!xs.length) return '–';
  const f = (x) => (x / scale).toFixed(digits);
  return xs.length === 1 ? f(xs[0]) : `${f(median(xs))} (${f(Math.min(...xs))}–${f(Math.max(...xs))})`;
};

const rows = [];
for (const label of labels) {
  const dir = join(results, label);
  if (!existsSync(dir)) continue;
  const runs = readdirSync(dir).map((d) => join(dir, d, 'result.json')).filter(existsSync)
    .map((f) => JSON.parse(readFileSync(f, 'utf8')));
  for (const task of [...new Set(runs.map((r) => r.task))].sort()) {
    const all = runs.filter((r) => r.task === task);
    const ok = all.filter((r) => !r.infraError);
    const get = (k) => ok.map((r) => r[k] ?? 0);
    rows.push(`| ${label} | ${task} | ${ok.filter((r) => r.pass).length}/${ok.length}` +
      `${all.length > ok.length ? ` (+${all.length - ok.length} infra)` : ''} | ${fmt(get('steps'))} | ${fmt(get('reads'))} | ` +
      `${fmt(get('redundantReads'))} | ${fmt(get('writes'))} | ${fmt(get('searches'))} | ${fmt(get('promptTokens'), 1000)} | ${fmt(get('peakPromptTokens'), 1000, 1)} | ` +
      `${fmt(ok.map((r) => (r.promptTokens ? (100 * r.cachedTokens) / r.llmPromptTokens : 0)))} | ` +
      `${fmt(get('wallSeconds'))} | ${[...new Set(ok.map((r) => r.stop))].join(', ')} |`);
  }
}
console.log('| variant | task | pass | steps | reads | redundant reads | writes | grep/glob | prompt K tokens | peak K | cached % | seconds | stop |');
console.log('| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |');
for (const r of rows) console.log(r);
