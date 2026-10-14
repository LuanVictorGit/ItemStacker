import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const targets = JSON.parse(readFileSync(join(here, 'targets.json'), 'utf8'));
const rows = [];
for (const file of readdirSync(join(here, 'results')).filter((f) => f.endsWith('.txt')).sort()) {
  const id = file.slice(0, -4);
  const lines = readFileSync(join(here, 'results', file), 'utf8').trim().split('\n');
  const total = lines[lines.length - 1].match(/(\d+) passed, (\d+) failed/);
  const target = targets[id] || {};
  rows.push(`| ${id} | ${target.java || '-'} | ${target.bot ? 'yes (' + target.bot + ')' : 'no'} | ${total ? total[1] : '?'} | ${total ? total[2] : '?'} |`);
}
console.log('| Target | Java | Protocol bot | Passed | Failed |\n| --- | --- | --- | --- | --- |\n' + rows.join('\n'));
