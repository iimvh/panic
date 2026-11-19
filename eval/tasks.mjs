// The eval tasks: a fixture to copy, the prompt (as a user would type it), an optional setup that plants a bug,
// and a deterministic check of the result. A check gets the workspace and the final answer and returns
// {pass, details}; it never asks a model.
import { readFileSync, writeFileSync, existsSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

const read = (ws, p) => (existsSync(join(ws, p)) ? readFileSync(join(ws, p), 'utf8') : '');

function files(dir, out = []) {
  for (const name of readdirSync(dir)) {
    if (name === 'node_modules' || name === '.panic' || name === 'dist') continue;
    const p = join(dir, name);
    if (statSync(p).isDirectory()) files(p, out);
    else out.push(p);
  }
  return out;
}

/** `vite build` in the workspace (node_modules is linked in by the runner). */
function builds(ws) {
  const r = spawnSync(process.execPath, [join(ws, 'node_modules', 'vite', 'bin', 'vite.js'), 'build', '--logLevel', 'error'],
    { cwd: ws, encoding: 'utf8', timeout: 180_000 });
  return { ok: r.status === 0, log: ((r.stdout || '') + (r.stderr || '')).slice(-600) };
}

export const tasks = {
  // The task that failed on 2026-11-18: 247 steps, 795 read_file, no code.
  t1: {
    fixture: 'mywps',
    prompt: 'hãy xây dựng thêm một page mới về blog cho nông trại cà phê, bạn là người code và agent khác sẽ là người review',
    check(ws) {
      const src = files(join(ws, 'src'));
      const blogFiles = src.filter((f) => /\.jsx$/.test(f) && /blog/i.test(f));
      const app = read(ws, 'src/App.jsx') + read(ws, 'src/main.jsx');
      const wired = /blog/i.test(app);
      const b = builds(ws);
      return { pass: blogFiles.length > 0 && wired && b.ok, details: { blogFiles: blogFiles.length, wired, build: b.ok, log: b.ok ? '' : b.log } };
    },
  },
  // A small change across three files, read and written in the right places.
  t2: {
    fixture: 'mywps',
    prompt: 'Thêm số hotline 0909 123 456 vào Header (cạnh nút "Đặt tour") và vào Footer. Giá trị phải lấy từ một trường mới `hotline` trong src/data/content.js, không viết cứng số điện thoại trong component.',
    check(ws) {
      const content = read(ws, 'src/data/content.js');
      const header = read(ws, 'src/components/Header.jsx');
      const footer = read(ws, 'src/components/Footer.jsx');
      const data = /hotline/.test(content) && /0909[\s.]?123[\s.]?456/.test(content);
      const uses = /hotline/.test(header) && /hotline/.test(footer);
      const hardcoded = /0909/.test(header + footer);
      const b = builds(ws);
      return { pass: data && uses && !hardcoded && b.ok, details: { data, uses, hardcoded, build: b.ok, log: b.ok ? '' : b.log } };
    },
  },
  // A planted bug: the cause is in a helper, not where the symptom shows; finding it needs a search.
  t3: {
    fixture: 'mywps',
    setup(ws) {
      const p = join(ws, 'src/components/Section.jsx');
      const s = readFileSync(p, 'utf8');
      const bugged = s.replace("return `${value.toLocaleString('vi-VN')} ₫`", "return `${(value / 10).toLocaleString('vi-VN')} ₫`");
      if (bugged === s) throw new Error('t3 setup: formatPrice not found');
      writeFileSync(p, bugged);
    },
    prompt: 'Khách báo giá trên trang bị sai: "Robusta Cổ Điển" giá 185.000 ₫ nhưng trang lại hiện 18.500 ₫. Tìm nguyên nhân và sửa. Không được sửa giá trong src/data/content.js.',
    check(ws) {
      const section = read(ws, 'src/components/Section.jsx');
      const content = read(ws, 'src/data/content.js');
      const fixed = !/value\s*\/\s*10/.test(section) && /toLocaleString\('vi-VN'\)/.test(section);
      const dataKept = /price:\s*185000/.test(content);
      const b = builds(ws);
      return { pass: fixed && dataKept && b.ok, details: { fixed, dataKept, build: b.ok, log: b.ok ? '' : b.log } };
    },
  },
  // Read-only exploration of a large repo (agent-panic at a fixed commit): where the answer is, and with what.
  t4: {
    fixture: 'panic-07940cd',
    prompt: 'Trong repo này, khi một turn của chat dài ra thì các tool result cũ bị xử lý thế nào? Chỉ ra hàm nào quyết định, ngưỡng mặc định là bao nhiêu, giữ lại bao nhiêu kết quả, kèm file và số dòng. Chỉ đọc, không sửa file nào.',
    check(ws, answer) {
      const a = answer || '';
      const fn = /clearToolResults/.test(a);
      const file = /ChatPlugin\.java/.test(a);
      const threshold = /16[ .,]?000|contextTokens\s*\/\s*2|half|một nửa|nửa/i.test(a);
      const keep = /keepToolResults|\b3\b/.test(a);
      return { pass: fn && file && threshold && keep, details: { fn, file, threshold, keep } };
    },
  },
};
