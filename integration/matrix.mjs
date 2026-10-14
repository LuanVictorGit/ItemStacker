import { spawn } from 'node:child_process';
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const parallel = Number((args.find((a) => a.startsWith('--parallel=')) || '--parallel=1').slice(11));
const passthrough = args.filter((a) => a.startsWith('--only='));
const queue = args.filter((a) => !a.startsWith('--'));

async function run(target) {
  const child = spawn(process.execPath, [join(here, 'run.mjs'), target, ...passthrough], { cwd: here, stdio: 'ignore', env: process.env });
  const code = await new Promise((resolve) => child.on('exit', resolve));
  const file = join(here, 'results', `${target}.txt`);
  const lines = existsSync(file) ? readFileSync(file, 'utf8').trim().split('\n') : [];
  const failures = lines.filter((l) => l.startsWith('FAIL'));
  console.log(`${lines[lines.length - 1] || target + ': no result'} (exit ${code})`);
  for (const line of failures) console.log('  ' + line);
}

await Promise.all(Array.from({ length: parallel }, async () => {
  for (let target = queue.shift(); target; target = queue.shift()) await run(target);
}));
console.log('matrix finished');
