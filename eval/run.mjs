#!/usr/bin/env node
// Runs Panic on eval tasks and records what each run did. No dependencies; see eval/README.md.
//
//   node eval/run.mjs --app <install dir> --variant <name> [--tasks t1,t2] [--reps 3] [--parallel 2]
//                     [--model provider/model] [--label name] [--timeout-min 25]
//
// Each run works in a clean copy of the fixture under the system temp directory (outside this repo: Panic reads
// the AGENTS.md / CLAUDE.md of every parent folder up to the git root, which would leak this repo's instructions
// into the run). Its panic.json is the repo's panic.json (providers, llm, chat, agents) + eval/base.json +
// eval/variants/<variant>.json. What is kept goes to eval/results/<label>/<task>-<rep>/: result.json, stdout,
// stderr, traces and conversations. A run that ended on a provider error (rate limit...) is retried.
import { cpSync, existsSync, mkdirSync, readFileSync, writeFileSync, rmSync, symlinkSync } from 'node:fs';
import { join, resolve, dirname } from 'node:path';
import { tmpdir } from 'node:os';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { tasks } from './tasks.mjs';
import { metrics } from './metrics.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, '..');
const RETRIES = 3;

function args() {
  const out = { tasks: 't1,t2,t3,t4', reps: 3, parallel: 2, timeoutMin: 25 };
  const argv = process.argv.slice(2);
  for (let i = 0; i < argv.length; i++) {
    const k = argv[i].replace(/^--/, '').replace(/-(\w)/g, (_, c) => c.toUpperCase());
    out[k] = argv[++i];
  }
  if (!out.app || !out.variant) {
    console.error('usage: node eval/run.mjs --app <install dir> --variant <name> [--tasks t1,t2] [--reps 3] [--parallel 2] [--model p/m] [--label name]');
    process.exit(2);
  }
  out.reps = Number(out.reps);
  out.parallel = Number(out.parallel);
  out.timeoutMin = Number(out.timeoutMin);
  out.label ??= `${out.variant}${out.model ? '-' + out.model.replace(/[^\w.-]+/g, '_') : ''}`;
  return out;
}

const isObject = (v) => v && typeof v === 'object' && !Array.isArray(v);
function merge(a, b) {
  const out = { ...a };
  for (const [k, v] of Object.entries(b || {})) out[k] = isObject(v) && isObject(out[k]) ? merge(out[k], v) : v;
  return out;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** A clean copy of the fixture, node_modules linked from a shared cache. */
function prepare(fixture, ws) {
  if (fixture.startsWith('panic-')) {
    const commit = fixture.slice('panic-'.length);
    const cache = join(here, '.cache', fixture);
    if (!existsSync(join(cache, 'AGENTS.md'))) {
      rmSync(cache, { recursive: true, force: true });
      mkdirSync(cache, { recursive: true });
      // through a pipe and the working directory: tar on Windows mangles drive-letter paths
      const a = spawnSync('git', ['archive', '--format=tar', commit], { cwd: repo, maxBuffer: 1 << 30 });
      if (a.status !== 0) throw new Error('git archive failed: ' + a.stderr);
      const x = spawnSync('tar', ['-xf', '-'], { cwd: cache, input: a.stdout, maxBuffer: 1 << 30 });
      if (x.status !== 0) throw new Error('tar failed: ' + x.stderr);
    }
    cpSync(cache, ws, { recursive: true });
    return;
  }
  cpSync(join(here, 'fixtures', fixture), ws, { recursive: true });
  if (existsSync(join(ws, 'package.json'))) {
    const modules = join(here, '.cache', fixture, 'node_modules');
    if (!existsSync(modules)) {
      const dir = join(here, '.cache', fixture);
      mkdirSync(dir, { recursive: true });
      cpSync(join(ws, 'package.json'), join(dir, 'package.json'));
      if (existsSync(join(ws, 'package-lock.json'))) cpSync(join(ws, 'package-lock.json'), join(dir, 'package-lock.json'));
      const npm = spawnSync('npm', ['ci', '--no-audit', '--no-fund'], { cwd: dir, encoding: 'utf8', shell: true });
      if (npm.status !== 0) throw new Error('npm ci failed: ' + npm.stderr);
    }
    symlinkSync(modules, join(ws, 'node_modules'), 'junction');
  }
}

function config(opts, run) {
  const user = JSON.parse(readFileSync(join(repo, 'panic.json'), 'utf8'));
  const base = JSON.parse(readFileSync(join(here, 'base.json'), 'utf8'));
  // the user's providers, model, chat and agents settings (what failed), then the eval's fixed environment
  let cfg = {};
  for (const k of ['openrouter', 'custom-providers', 'llm', 'chat', 'agents']) if (user[k]) cfg[k] = user[k];
  cfg = merge(cfg, base);
  cfg = merge(cfg, JSON.parse(readFileSync(join(here, 'variants', opts.variant + '.json'), 'utf8')));
  if (opts.model) {
    const [provider, ...rest] = opts.model.split('/');
    cfg = merge(cfg, { llm: { provider, model: rest.join('/') } });
  }
  // rate limits are the provider's, not the agent's: retry them rather than lose the run
  for (const p of Object.values(cfg['custom-providers']?.providers || {})) p.maxRetries = Math.max(p.maxRetries || 0, 5);
  if (cfg.openrouter) cfg.openrouter.maxRetries = Math.max(cfg.openrouter.maxRetries || 0, 5);
  cfg = merge(cfg, {
    conversations: { dir: join(run, 'conversations'), persist: true },
    tracing: { dir: join(run, 'traces') },
    workspace: { dir: join(run, 'workspace-state') },
  });
  return cfg;
}

function attempt(opts, taskId, rep) {
  const task = tasks[taskId];
  const run = join(tmpdir(), 'panic-eval', opts.label, `${taskId}-${rep}`);
  const kept = resolve(here, 'results', opts.label, `${taskId}-${rep}`);
  rmSync(run, { recursive: true, force: true });
  rmSync(kept, { recursive: true, force: true });
  const ws = join(run, 'ws');
  mkdirSync(run, { recursive: true });
  prepare(task.fixture, ws);
  task.setup?.(ws);
  const cfgPath = join(run, 'panic.json');
  writeFileSync(cfgPath, JSON.stringify(config(opts, run), null, 2));
  const bin = join(resolve(opts.app), 'bin', process.platform === 'win32' ? 'app.bat' : 'app');
  const started = Date.now();
  return new Promise((done) => {
    // the prompt goes in on stdin (the REPL reads UTF-8): Java decodes Windows command lines with the ANSI code
    // page, which turns Vietnamese into '?'. The REPL runs the line, then exits at the end of input.
    const child = spawn(bin, ['-c', cfgPath], { cwd: ws, shell: process.platform === 'win32', windowsHide: true });
    child.stdin.end(task.prompt.replace(/\s*\n\s*/g, ' ') + '\n', 'utf8');
    let out = '';
    let err = '';
    child.stdout.on('data', (d) => (out += d));
    child.stderr.on('data', (d) => (err += d));
    let timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      if (process.platform === 'win32') spawnSync('taskkill', ['/pid', String(child.pid), '/T', '/F']);
      else child.kill('SIGKILL');
    }, opts.timeoutMin * 60_000);
    child.on('close', (code) => {
      clearTimeout(timer);
      // the config holds API keys: never keep it
      rmSync(cfgPath, { force: true });
      const m = metrics(run);
      let check;
      try {
        check = task.check(ws, m.answer);
      } catch (e) {
        check = { pass: false, details: { error: String(e) } };
      }
      // any [error] line is the ask failing (provider, network), not the agent: such a run says nothing about it
      const providerError = /\[error\][^\n]*/.exec(out);
      const result = { task: taskId, rep, variant: opts.variant, label: opts.label, model: opts.model || null,
        exit: code, timedOut, infraError: providerError ? providerError[0] : null,
        wallSeconds: Math.round((Date.now() - started) / 1000), pass: check.pass, check: check.details, ...m };
      delete result.answer;
      result.answerChars = (m.answer || '').length;
      mkdirSync(kept, { recursive: true });
      writeFileSync(join(kept, 'result.json'), JSON.stringify(result, null, 2));
      writeFileSync(join(kept, 'stdout.txt'), out);
      writeFileSync(join(kept, 'stderr.txt'), err);
      writeFileSync(join(kept, 'answer.md'), m.answer || '');
      for (const d of ['traces', 'conversations']) if (existsSync(join(run, d))) cpSync(join(run, d), join(kept, d), { recursive: true });
      rmSync(run, { recursive: true, force: true });
      done(result);
    });
  });
}

async function once(opts, taskId, rep) {
  for (let i = 0; ; i++) {
    const r = await attempt(opts, taskId, rep);
    const line = `${opts.label} ${taskId}#${rep}: ${r.pass ? 'PASS' : 'FAIL'} steps=${r.steps} reads=${r.reads} ` +
      `redundant=${r.redundantReads} writes=${r.writes} prompt=${r.promptTokens} cached=${r.cachedTokens} ` +
      `${r.wallSeconds}s stop=${r.stop}`;
    if (!r.infraError || i >= RETRIES) {
      console.log(line + (r.infraError ? ` INFRA(${r.infraError})` : ''));
      return r;
    }
    console.log(`${line} -- provider error (${r.infraError}), retrying in 60s`);
    await sleep(60_000);
  }
}

async function main() {
  const opts = args();
  const jobs = [];
  for (let r = 1; r <= opts.reps; r++) for (const t of opts.tasks.split(',')) jobs.push([t, r]);
  const results = [];
  let next = 0;
  async function worker() {
    while (next < jobs.length) {
      const [t, r] = jobs[next++];
      try {
        results.push(await once(opts, t, r));
      } catch (e) {
        console.error(`${opts.label} ${t}#${r}: ERROR ${e.stack || e}`);
      }
    }
  }
  await Promise.all(Array.from({ length: Math.max(1, opts.parallel) }, worker));
  console.log(`done: ${results.filter((r) => r.pass).length}/${results.length} passed`);
}

main();
