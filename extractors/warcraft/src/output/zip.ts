import { closeSync, openSync, writeSync } from 'node:fs';
import { crc32, deflateRawSync } from 'node:zlib';

/**
 * Writes a ZIP file entry by entry. PNGs are stored (already compressed), other
 * files deflated. No ZIP64: each entry and the archive must stay under 4 GB.
 */
export class ZipWriter {
  private readonly fd: number;
  private offset = 0;
  private readonly central: Buffer[] = [];
  private count = 0;

  constructor(path: string) {
    this.fd = openSync(path, 'w');
  }

  add(name: string, data: Buffer): void {
    const store = name.endsWith('.png');
    const body = store ? data : deflateRawSync(data, { level: 9 });
    if (this.offset + body.length > 0xffffffff) throw new Error('ZIP file larger than 4 GB');
    const nameBytes = Buffer.from(name, 'utf8');
    const crc = crc32(data) >>> 0;
    const method = store ? 0 : 8;
    const { time, date } = dosDateTime(new Date());

    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4); // version needed
    local.writeUInt16LE(0x0800, 6); // UTF-8 names
    local.writeUInt16LE(method, 8);
    local.writeUInt16LE(time, 10);
    local.writeUInt16LE(date, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(body.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBytes.length, 26);

    const entry = Buffer.alloc(46);
    entry.writeUInt32LE(0x02014b50, 0);
    entry.writeUInt16LE(20, 4); // version made by
    entry.writeUInt16LE(20, 6);
    local.copy(entry, 8, 6, 26); // flags .. uncompressed size
    entry.writeUInt16LE(nameBytes.length, 28);
    entry.writeUInt32LE(this.offset, 42);
    this.central.push(entry, nameBytes);

    this.write(local);
    this.write(nameBytes);
    this.write(body);
    this.count++;
  }

  close(): void {
    const start = this.offset;
    for (const part of this.central) this.write(part);
    const end = Buffer.alloc(22);
    end.writeUInt32LE(0x06054b50, 0);
    end.writeUInt16LE(this.count, 8);
    end.writeUInt16LE(this.count, 10);
    end.writeUInt32LE(this.offset - start, 12);
    end.writeUInt32LE(start, 16);
    this.write(end);
    closeSync(this.fd);
  }

  private write(data: Buffer): void {
    writeSync(this.fd, data);
    this.offset += data.length;
  }
}

function dosDateTime(d: Date): { time: number; date: number } {
  return {
    time: (d.getHours() << 11) | (d.getMinutes() << 5) | (d.getSeconds() >> 1),
    date: ((d.getFullYear() - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate(),
  };
}
