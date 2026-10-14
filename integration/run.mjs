import { readFileSync, existsSync, mkdirSync, writeFileSync, copyFileSync, readdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { prepare, start } from './server.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const targets = JSON.parse(readFileSync(join(here, 'targets.json'), 'utf8'));
const javas = {
  '8': process.env.JAVA_8 || 'java',
  '21': process.env.JAVA_21 || 'C:/Program Files/Java/jdk-21/bin/java.exe',
  '25': process.env.JAVA_25 || 'C:/Program Files/Java/jdk-25/bin/java.exe',
};
const id = process.argv[2];
const only = (process.argv.find((a) => a.startsWith('--only=')) || '').slice(7).split(',').filter(Boolean);
const target = targets[id];
if (!target) throw new Error('unknown target ' + id + ', choose one of ' + Object.keys(targets).join(', '));
const port = 25570 + Object.keys(targets).indexOf(id);
const work = process.env.WORK_DIR ? join(process.env.WORK_DIR, id) : join(here, '.work', id);
const wanted = (name) => only.length === 0 || only.includes(name);

const testConfig = `Config:
  tag: "&6[ItemStacker]"
  blacklist:
    - "diamond_sword"
  ignored_worlds: []
  stack_custom_items: true
  stack_unstackable_items: true
  displayname_active: true
  displayname_always: true
  displayname_format: "&b{displayname} &a{amount}x"
  displayname_min_amount: 2
  view_range: 48
  visual_interval: 2
  delayCheckItems: 500
  stack_size: 100000
  stack_limits:
    DIRT: 1000
  stack_distance: 8
  merge_on_spawn: true
  playsound: true
  budget: 1500
  slice_ms: 5
  queue_limit: 4096
  rescan_seconds: 5
  save_seconds: 5
  debug: false
`;

async function serverJar() {
  if (target.jar) return target.jar;
  const meta = await (await fetch(`https://fill.papermc.io/v3/projects/${target.project}/versions/${target.version}/builds/latest`)).json();
  const file = meta.downloads['server:default'];
  const path = join(here, '.cache', file.name);
  if (!existsSync(path)) {
    mkdirSync(dirname(path), { recursive: true });
    const buffer = Buffer.from(await (await fetch(file.url)).arrayBuffer());
    if (createHash('sha256').update(buffer).digest('hex') !== file.checksums.sha256) throw new Error('checksum mismatch for ' + file.name);
    writeFileSync(path, buffer);
  }
  return path;
}

function pluginJars() {
  const targetDir = join(here, '..', 'target');
  const main = readdirSync(targetDir).find((f) => /^ItemStacker-[0-9][0-9.]*\.jar$/.test(f));
  const harnessJar = join(here, 'harness', 'target', 'ItemStackerIT.jar');
  if (!existsSync(harnessJar)) spawnSync('mvn', ['-q', '-f', join(here, 'harness', 'pom.xml'), 'package'], { stdio: 'inherit', shell: true });
  return [join(targetDir, main), harnessJar];
}

function boot(jar, fresh) {
  const jars = pluginJars();
  if (fresh) {
    prepare(work, jar, { 'server-port': String(port), 'level-type': target.levelType, gamemode: 'survival', difficulty: 'peaceful', 'force-gamemode': 'true', 'online-mode': 'false' });
    mkdirSync(join(work, 'plugins', 'ItemStacker'), { recursive: true });
    writeFileSync(join(work, 'plugins', 'ItemStacker', 'config.yml'), testConfig);
    copyFileSync(jars[0], join(work, 'plugins', 'ItemStacker.jar'));
    copyFileSync(jars[1], join(work, 'plugins', 'ItemStackerIT.jar'));
  }
  return start(work, javas[target.java], ['-Xmx2G', '-Djdk.net.unixdomain.tmpdir=C:/Temp', '-DPaper.IgnoreJavaVersion=true', ...(target.jvm || [])]);
}

function spawnBot(name) {
  const out = join(work, `bot-${name}.jsonl`);
  const child = spawn(process.execPath, [join(here, 'bot.mjs'), '127.0.0.1', String(port), target.bot, name, out], { cwd: here, stdio: ['pipe', 'inherit', 'inherit'] });
  return { out, quit: () => child.stdin.write('quit\n'), exited: new Promise((resolve) => child.on('exit', resolve)) };
}

const results = [];
async function phase(server, name, timeout = 300000) {
  const mark = server.lines.length;
  server.send('itrun ' + name);
  await server.waitFor(new RegExp(`\\[IT\\] DONE ${name} `), timeout);
  for (const line of server.lines.slice(mark)) {
    const m = line.match(/\[IT\] (PASS|FAIL) (\S+) (.*)$/);
    if (m) results.push({ status: m[1], name: m[2], detail: m[3] });
  }
}

function lastNames(file) {
  const names = new Map();
  for (const line of readFileSync(file, 'utf8').split('\n')) {
    if (!line.trim()) continue;
    const record = JSON.parse(line);
    if (record.type === 'meta' && record.name !== undefined) names.set(record.id, record);
  }
  return names;
}

function records(file) {
  return readFileSync(file, 'utf8').split(/\r?\n/).filter((l) => l.trim()).map((l) => JSON.parse(l));
}

function checkPiles(server, file, label) {
  const names = lastNames(file);
  const piles = server.lines.map((l) => l.match(/\[IT\] PILE id=(\d+) total=(\d+) step=(\d+) label=(.+)$/)).filter(Boolean);
  const last = piles[piles.length - 1];
  if (!last) {
    results.push({ status: 'FAIL', name: label, detail: 'no PILE line' });
    return;
  }
  const record = names.get(Number(last[1]));
  const text = record ? JSON.stringify(record.name) : '';
  const visible = Boolean(record) && (record.visible === true || record.visible === 1);
  const ok = Boolean(record) && text.includes(`${last[2]}x`) && text.includes(last[4]) && visible;
  results.push({ status: ok ? 'PASS' : 'FAIL', name: label, detail: `entity=${last[1]} expected=${last[2]}x seen=${text.slice(0, 160)} visible=${visible}` });
}

function checkClear(server, file) {
  const clears = server.lines.map((l) => l.match(/\[IT\] CLEAR id=(\d+) total=(\d+) step=(\d+)/)).filter(Boolean);
  if (clears.length < 2) {
    results.push({ status: 'FAIL', name: 'visual-bot-name-cleared', detail: 'no CLEAR lines' });
    return;
  }
  const id = Number(clears[0][1]);
  const mine = records(file).filter((r) => r.type === 'meta' && r.id === id && r.name !== undefined);
  const shown = mine.some((r) => JSON.stringify(r.name).includes('3x'));
  const last = mine[mine.length - 1];
  const text = last ? JSON.stringify(last.name) : '';
  const cleared = Boolean(last) && (last.name === null || text === '""' || text === 'null' || !/[A-Za-z0-9]/.test(text));
  const hidden = Boolean(last) && (last.visible === false || last.visible === 0);
  results.push({ status: shown && cleared && hidden ? 'PASS' : 'FAIL', name: 'visual-bot-name-cleared', detail: `entity=${id} sawNameBefore=${shown} lastName=${text.slice(0, 80)} visible=${last && last.visible}` });
}

function checkRetrack(server, file) {
  const index = server.lines.findIndex((l) => /\[IT\] RETRACK id=/.test(l));
  if (index < 0) {
    results.push({ status: 'FAIL', name: 'visual-bot-retrack', detail: 'no RETRACK line' });
    return;
  }
  const match = server.lines[index].match(/id=(\d+) total=(\d+)/);
  const since = server.stamps[index];
  const seen = records(file).filter((r) => r.type === 'meta' && r.id === Number(match[1]) && r.at >= since && r.name !== undefined);
  const ok = seen.some((r) => JSON.stringify(r.name).includes(`${match[2]}x`));
  results.push({ status: ok ? 'PASS' : 'FAIL', name: 'visual-bot-retrack', detail: `entity=${match[1]} expected=${match[2]}x updatesAfterReturn=${seen.length}` });
}

const jar = await serverJar();
let server = boot(jar, true);
let tester;
try {
  await server.waitFor(/Done \(/, 600000);
  if (target.bot) {
    tester = spawnBot('Tester');
    await server.waitFor(/Tester.*(joined the game|logged in with entity id)/, 120000);
    await new Promise((r) => setTimeout(r, 4000));
  }
  for (const name of ['main', 'vanilla', 'cleanup', 'unload', 'stress', 'millions', 'regions']) if (wanted(name)) await phase(server, name, 600000);
  if (only.includes('lifecycle')) await phase(server, 'lifecycle');
  if (target.bot && wanted('control')) await phase(server, 'control');
  if (target.bot && wanted('player')) await phase(server, 'player');
  if (target.bot && wanted('visual')) {
    await phase(server, 'visual');
    await new Promise((r) => setTimeout(r, 3000));
    checkPiles(server, tester.out, 'visual-bot-live');
    checkClear(server, tester.out);
    checkRetrack(server, tester.out);
    const late = spawnBot('Late');
    await server.waitFor(/Late.*(joined the game|logged in with entity id)/, 120000);
    await new Promise((r) => setTimeout(r, 4000));
    checkPiles(server, late.out, 'visual-bot-late-join');
    late.quit();
    await late.exited;
  }
  if (wanted('persist')) {
    await phase(server, 'persist-create');
    if (tester) {
      tester.quit();
      await tester.exited;
      tester = null;
    }
    await server.stop();
    server = boot(jar, false);
    await server.waitFor(/Done \(/, 600000);
    await phase(server, 'persist-verify');
  }
} catch (error) {
  results.push({ status: 'FAIL', name: 'runner', detail: String(error) });
} finally {
  if (tester) {
    tester.quit();
    await Promise.race([tester.exited, new Promise((r) => setTimeout(r, 5000))]);
  }
  await server.stop();
}

let failed = 0;
for (const r of results) {
  if (r.status === 'FAIL') failed++;
  console.log(`${r.status} ${r.name} ${r.detail}`);
}
console.log(`\n${id}: ${results.length - failed} passed, ${failed} failed`);
mkdirSync(join(here, 'results'), { recursive: true });
writeFileSync(join(here, 'results', `${id}.txt`), results.map((r) => `${r.status} ${r.name} ${r.detail}`).join('\n') + `\n${id}: ${results.length - failed} passed, ${failed} failed\n`);
process.exit(failed ? 1 : 0);
