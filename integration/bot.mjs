import mc from 'minecraft-protocol';
import { createWriteStream } from 'node:fs';

const [host, port, version, username, out] = process.argv.slice(2);
const log = createWriteStream(out);
const started = Date.now();
const emit = (record) => log.write(JSON.stringify({ t: Date.now() - started, at: Date.now(), ...record }) + '\n');
const client = mc.createClient({ host, port: Number(port), username, version, auth: 'offline', checkTimeoutInterval: 10 * 60 * 1000 });

const countOf = (value) => {
  if (!value || typeof value !== 'object') return undefined;
  for (const key of ['itemCount', 'count', 'Count']) if (typeof value[key] === 'number') return value[key];
  return undefined;
};

let at = null;
let moving = false;
let tick = 0;
client.on('login', () => emit({ type: 'login' }));
client.on('position', (packet) => {
  if (packet.teleportId !== undefined) client.write('teleport_confirm', { teleportId: packet.teleportId });
  at = { x: packet.x, y: packet.y, z: packet.z, yaw: packet.yaw || 0, pitch: packet.pitch || 0 };
  if (!moving) {
    moving = true;
    setInterval(() => {
      try {
        const shift = (tick++ % 2) * 0.002;
        client.write('position_look', { x: at.x + shift, y: at.y, z: at.z, yaw: at.yaw, pitch: at.pitch, onGround: true, flags: 1 });
      } catch (error) {
        emit({ type: 'error', message: String(error) });
      }
    }, 50);
  }
});
client.on('chunk_batch_finished', () => {
  try {
    client.write('chunk_batch_received', { chunksPerTick: 20 });
  } catch (error) {
    emit({ type: 'error', message: String(error) });
  }
});
client.on('error', (error) => emit({ type: 'error', message: String(error) }));
client.on('end', (reason) => {
  emit({ type: 'end', reason: String(reason) });
  log.end(() => process.exit(0));
});
client.on('packet', (data, meta) => {
  if (meta.state !== 'play') return;
  if (meta.name === 'entity_metadata' && Array.isArray(data.metadata)) {
    const record = { type: 'meta', id: data.entityId };
    for (const entry of data.metadata) {
      if (entry.key === 2) record.name = entry.value === undefined ? null : entry.value;
      if (entry.key === 3) record.visible = entry.value;
      const count = countOf(entry.value);
      if (count !== undefined) record.count = count;
    }
    if (record.name !== undefined || record.count !== undefined) emit(record);
  } else if (meta.name === 'entity_destroy' || meta.name === 'remove_entities') {
    emit({ type: 'destroy', ids: data.entityIds });
  }
});
process.stdin.on('data', (chunk) => {
  if (String(chunk).includes('quit')) client.end('bye');
});
setTimeout(() => client.end('timeout'), 30 * 60 * 1000);
