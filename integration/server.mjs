import { spawn } from 'node:child_process';
import { createWriteStream, mkdirSync, writeFileSync, existsSync, copyFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';

export function prepare(dir, jar, props = {}) {
  rmSync(dir, { recursive: true, force: true });
  mkdirSync(join(dir, 'plugins'), { recursive: true });
  copyFileSync(jar, join(dir, 'server.jar'));
  writeFileSync(join(dir, 'eula.txt'), 'eula=true\n');
  const merged = {
    'online-mode': 'false',
    'server-port': '25599',
    'level-type': 'flat',
    'spawn-protection': '0',
    'view-distance': '4',
    'simulation-distance': '4',
    'max-tick-time': '-1',
    'allow-nether': 'false',
    'spawn-monsters': 'false',
    'spawn-animals': 'false',
    'generate-structures': 'false',
    'enable-command-block': 'false',
    'motd': 'ItemStacker integration',
    ...props,
  };
  writeFileSync(join(dir, 'server.properties'), Object.entries(merged).map(([k, v]) => `${k}=${v}`).join('\n') + '\n');
}

export function start(dir, java, args = []) {
  const log = createWriteStream(join(dir, 'console.log'));
  const child = spawn(java, [...args, '-jar', 'server.jar', 'nogui'], { cwd: dir, stdio: ['pipe', 'pipe', 'pipe'] });
  const lines = [];
  const stamps = [];
  const waiters = [];
  let buffer = '';
  const feed = (chunk) => {
    log.write(chunk);
    buffer += chunk.toString();
    let at;
    while ((at = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, at).replace(/\r$/, '');
      buffer = buffer.slice(at + 1);
      lines.push(line);
      stamps.push(Date.now());
      for (const w of [...waiters]) if (w.test(line)) { waiters.splice(waiters.indexOf(w), 1); clearTimeout(w.timer); w.resolve(line); }
    }
  };
  child.stdout.on('data', feed);
  child.stderr.on('data', feed);
  const exited = new Promise((resolve) => child.on('exit', (code) => { log.end(); resolve(code); }));
  return {
    child, lines, stamps, exited,
    send: (cmd) => child.stdin.write(cmd + '\n'),
    waitFor(regex, ms = 120000) {
      const found = lines.find((l) => regex.test(l));
      if (found) return Promise.resolve(found);
      return new Promise((resolve, reject) => {
        const w = { test: (l) => regex.test(l), resolve };
        w.timer = setTimeout(() => { waiters.splice(waiters.indexOf(w), 1); reject(new Error('timeout waiting for ' + regex)); }, ms);
        waiters.push(w);
      });
    },
    async stop() {
      if (child.exitCode === null) {
        child.stdin.write('stop\n');
        const timer = setTimeout(() => child.kill(), 60000);
        await exited;
        clearTimeout(timer);
      }
    },
  };
}
