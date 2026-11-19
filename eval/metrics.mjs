// What one run did, from its traces (every tool call is a span, saved even when the turn is not) and its
// conversations (the calls' arguments, for builds whose spans do not say which file a read was about).
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const jsonl = (p) => readFileSync(p, 'utf8').split('\n').filter((l) => l.trim()).map((l) => JSON.parse(l));
const listJsonl = (dir) => (existsSync(dir) ? readdirSync(dir).filter((f) => f.endsWith('.jsonl')).map((f) => join(dir, f)) : []);
const ERROR = /^error: /;

/** Reads of a file part already read, at the same version, with no write to the file in between. */
function redundant(events) {
  const lastRead = new Map();
  const written = new Map();
  let n = 0;
  events.forEach((e, i) => {
    if (e.kind === 'read') {
      const key = `${e.scope}|${e.file}|${e.part}|${e.version || ''}`;
      const before = lastRead.get(key);
      if (before !== undefined && (written.get(`${e.scope}|${e.file}`) ?? -1) < before) n++;
      lastRead.set(key, i);
    } else if (e.kind === 'write') {
      written.set(`${e.scope}|${e.file}`, i);
    }
  });
  return n;
}

export function metrics(run) {
  const spans = listJsonl(join(run, 'traces')).flatMap(jsonl).sort((a, b) => a.startMicros - b.startMicros);
  const turns = spans.filter((s) => s.name === 'turn');
  const main = turns.find((s) => !s.attrs?.agent) || {};
  const llm = spans.filter((s) => s.name === 'llm');
  const tools = spans.filter((s) => s.name === 'tool');
  const sum = (xs, f) => xs.reduce((a, x) => a + (f(x) || 0), 0);
  const ok = (s) => s.status === 'OK';
  const named = (...names) => tools.filter((s) => names.includes(s.attrs?.tool));

  // which files were read: from the spans when they say (newer builds), else from the conversations' arguments
  let events = [];
  if (tools.some((s) => s.attrs?.resource)) {
    events = tools.filter(ok).map((s) => {
      const a = s.attrs || {};
      if (a.tool === 'read_file' && a.resource && !a.unchanged) return { kind: 'read', scope: s.parentId, file: a.resource, part: a.lines, version: a.version };
      if ((a.tool === 'write_file' || a.tool === 'edit_file') && a.resource) return { kind: 'write', scope: s.parentId, file: a.resource };
      return { kind: 'other' };
    });
  }
  let answer = '';
  const files = new Set();
  for (const file of listJsonl(join(run, 'conversations'))) {
    const msgs = jsonl(file).filter((m) => m.role);
    const results = new Map(msgs.filter((m) => m.role === 'tool').map((m) => [m.toolCallId, m.content]));
    const isMain = !file.includes('~');
    const conversationEvents = [];
    for (const m of msgs) {
      for (const c of m.toolCalls || []) {
        let a = {};
        try { a = JSON.parse(c.arguments); } catch { /* kept as {} */ }
        const path = String(a.path || '').replace(/\\/g, '/').replace(/^\.\//, '');
        const result = results.get(c.id) ?? '';
        if (c.name === 'read_file') files.add(path);
        if (ERROR.test(result)) continue;
        if (c.name === 'read_file') conversationEvents.push({ kind: 'read', scope: file, file: path, part: `${a.offset || 1}+${a.limit || ''}` });
        else if (c.name === 'write_file' || c.name === 'edit_file') conversationEvents.push({ kind: 'write', scope: file, file: path });
      }
      if (isMain && m.role === 'assistant' && !(m.toolCalls || []).length && m.content) answer = m.content;
    }
    if (!tools.some((s) => s.attrs?.resource)) events.push(...conversationEvents);
  }
  for (const s of named('read_file')) if (s.attrs?.resource) files.add(s.attrs.resource);

  return {
    stop: main.attrs?.stop ?? (main.status || 'none'),
    steps: sum(turns, (t) => t.attrs?.steps),
    llmCalls: llm.length,
    toolCalls: tools.length,
    reads: named('read_file').length,
    uniqueFilesRead: files.size,
    redundantReads: redundant(events),
    unchangedHits: sum(tools, (s) => (s.attrs?.unchanged ? 1 : 0)),
    refetches: sum(tools, (s) => (s.attrs?.refetch ? 1 : 0)),
    writes: named('write_file', 'edit_file').filter(ok).length,
    editFailures: named('edit_file').filter((s) => !ok(s)).length,
    searches: named('grep', 'glob').length,
    execs: named('exec').length,
    subAgents: turns.length - (main.attrs ? 1 : 0),
    // the main turn counts what its sub-agents spent (their tool results carry it)
    promptTokens: main.attrs?.promptTokens ?? sum(llm, (s) => s.attrs?.promptTokens),
    completionTokens: main.attrs?.completionTokens ?? sum(llm, (s) => s.attrs?.completionTokens),
    cachedTokens: sum(llm, (s) => s.attrs?.cachedTokens),
    llmPromptTokens: sum(llm, (s) => s.attrs?.promptTokens),
    peakPromptTokens: Math.max(0, ...llm.map((s) => s.attrs?.promptTokens || 0)),
    clearedMax: Math.max(0, ...llm.map((s) => s.attrs?.clearedToolResults || 0)),
    contextTokens: main.attrs?.contextTokens ?? null,
    turnCompactions: sum(turns, (t) => t.attrs?.turnCompactions),
    answer,
  };
}
