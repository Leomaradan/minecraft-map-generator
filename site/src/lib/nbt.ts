import { gunzipSync } from 'fflate';

/** Reads the "size" list of a structure template .nbt (gzip), or null if it can't be found. */
export function readNbtSize(bytes: Uint8Array): [number, number, number] | null {
  let data: Uint8Array;
  try {
    data = gunzipSync(bytes);
  } catch {
    data = bytes;
  }
  const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
  const dec = new TextDecoder();
  let pos = 0;

  const skipPayload = (tag: number): void => {
    switch (tag) {
      case 1: pos += 1; break;
      case 2: pos += 2; break;
      case 3: case 5: pos += 4; break;
      case 4: case 6: pos += 8; break;
      case 7: pos += 4 + view.getInt32(pos); break;
      case 8: pos += 2 + view.getUint16(pos); break;
      case 9: {
        const t = view.getInt8(pos);
        const len = view.getInt32(pos + 1);
        pos += 5;
        for (let i = 0; i < len; i++) skipPayload(t);
        break;
      }
      case 10:
        while (view.getInt8(pos) !== 0) {
          const t = view.getInt8(pos);
          pos += 1;
          pos += 2 + view.getUint16(pos);
          skipPayload(t);
        }
        pos += 1;
        break;
      case 11: pos += 4 + 4 * view.getInt32(pos); break;
      case 12: pos += 4 + 8 * view.getInt32(pos); break;
      default: throw new Error('bad tag');
    }
  };
  try {
    if (view.getInt8(pos) !== 10) return null;
    pos += 1;
    pos += 2 + view.getUint16(pos); // root name
    while (view.getInt8(pos) !== 0) {
      const t = view.getInt8(pos);
      pos += 1;
      const nameLen = view.getUint16(pos);
      const name = dec.decode(data.subarray(pos + 2, pos + 2 + nameLen));
      pos += 2 + nameLen;
      if (name === 'size' && t === 9) {
        const itemTag = view.getInt8(pos);
        const len = view.getInt32(pos + 1);
        if (itemTag !== 3 || len !== 3) return null;
        return [view.getInt32(pos + 5), view.getInt32(pos + 9), view.getInt32(pos + 13)];
      }
      skipPayload(t);
    }
  } catch {
    return null;
  }
  return null;
}
