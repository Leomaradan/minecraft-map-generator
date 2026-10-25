import { readChunks } from './chunks.ts';

export interface WmoRoot {
  /** FileDataIDs of the groups (base level of detail). */
  groups: number[];
  /** FileDataID of the main texture of each material (0 if none). */
  materialTextures: number[];
}

export interface WmoGroup {
  /** x, y, z per vertex, model space (z up). */
  vertices: Float32Array;
  /** 3 vertex indices per triangle. */
  indices: Uint32Array;
  /** Material of each triangle, 0xFF for collision-only faces. */
  materials: Uint8Array;
}

export function readWmoRoot(data: Buffer): WmoRoot {
  const chunks = readChunks(data);
  const mohd = chunks.find((c) => c.id === 'MOHD');
  const gfid = chunks.find((c) => c.id === 'GFID');
  const momt = chunks.find((c) => c.id === 'MOMT');
  // Since 8.1, textures are FileDataIDs (no MOTX name table).
  const materialTextures: number[] = [];
  if (momt && !chunks.some((c) => c.id === 'MOTX')) {
    for (let p = momt.start; p + 64 <= momt.start + momt.size; p += 64) materialTextures.push(data.readUInt32LE(p + 12));
  }
  if (!mohd || !gfid) return { groups: [], materialTextures };
  const count = Math.min(data.readUInt32LE(mohd.start + 4), gfid.size / 4);
  const groups: number[] = [];
  for (let i = 0; i < count; i++) {
    const id = data.readUInt32LE(gfid.start + i * 4);
    if (id) groups.push(id);
  }
  return { groups, materialTextures };
}

const F_DETAIL = 0x04;
const F_COLLISION = 0x08;
const F_RENDER = 0x20;

/**
 * Reads the triangles of a WMO group (MOVT vertices, MOVI or MOVX indices).
 * `solid` keeps those players collide with, dropping decorations (clouds, wind
 * streams, foliage); `visible` keeps every rendered face plus the collision faces.
 */
export function readWmoGroup(data: Buffer, mode: 'solid' | 'visible' = 'solid'): WmoGroup {
  const mogp = readChunks(data).find((c) => c.id === 'MOGP');
  let vertices = new Float32Array(0);
  let indices = new Uint32Array(0);
  let triangleFlags: Buffer | undefined;
  if (!mogp) return { vertices, indices, materials: new Uint8Array(0) };
  // MOGP has a 68-byte header, then sub-chunks.
  for (const sub of readChunks(data, mogp.start + 68, mogp.start + mogp.size)) {
    if (sub.id === 'MOVT') {
      vertices = new Float32Array(sub.size / 4);
      for (let i = 0; i < vertices.length; i++) vertices[i] = data.readFloatLE(sub.start + i * 4);
    } else if (sub.id === 'MOVI') {
      indices = new Uint32Array(sub.size / 2);
      for (let i = 0; i < indices.length; i++) indices[i] = data.readUInt16LE(sub.start + i * 2);
    } else if (sub.id === 'MOVX') {
      indices = new Uint32Array(sub.size / 4);
      for (let i = 0; i < indices.length; i++) indices[i] = data.readUInt32LE(sub.start + i * 4);
    } else if (sub.id === 'MOPY') {
      // 2 bytes per triangle: flags, material.
      triangleFlags = data.subarray(sub.start, sub.start + sub.size);
    }
  }
  if (!triangleFlags || triangleFlags.length / 2 !== indices.length / 3) {
    return { vertices, indices, materials: new Uint8Array(indices.length / 3).fill(0xff) };
  }
  const kept: number[] = [];
  const materials: number[] = [];
  for (let t = 0; t < indices.length / 3; t++) {
    const flags = triangleFlags[t * 2];
    const material = triangleFlags[t * 2 + 1];
    const keep =
      mode === 'solid' ? flags & F_COLLISION || (flags & F_RENDER && !(flags & F_DETAIL)) : flags & F_COLLISION || material !== 0xff;
    if (!keep) continue;
    kept.push(indices[t * 3], indices[t * 3 + 1], indices[t * 3 + 2]);
    materials.push(material);
  }
  return { vertices, indices: Uint32Array.from(kept), materials: Uint8Array.from(materials) };
}

export interface WmoPlacement {
  fileDataId: number;
  uniqueId: number;
  /** Position in placement space: x and z on the map (from its north-west corner), y up. */
  position: [number, number, number];
  /** Rotation in degrees. */
  rotation: [number, number, number];
  /** Bounding box in placement space: min x, y, z then max x, y, z. */
  extents: [number, number, number, number, number, number];
  scale: number;
}

const NAME_IS_FILE_DATA_ID = 0x8;

/** Reads the WMO placements (MODF) of an obj0 ADT. Placements by file name are skipped. */
export function readWmoPlacements(data: Buffer): WmoPlacement[] {
  const modf = readChunks(data).find((c) => c.id === 'MODF');
  if (!modf) return [];
  const placements: WmoPlacement[] = [];
  for (let p = modf.start; p + 64 <= modf.start + modf.size; p += 64) {
    if (!(data.readUInt16LE(p + 56) & NAME_IS_FILE_DATA_ID)) continue;
    const f = (offset: number) => data.readFloatLE(p + offset);
    const scale = data.readUInt16LE(p + 62);
    placements.push({
      fileDataId: data.readUInt32LE(p),
      uniqueId: data.readUInt32LE(p + 4),
      position: [f(8), f(12), f(16)],
      rotation: [f(20), f(24), f(28)],
      extents: [f(32), f(36), f(40), f(44), f(48), f(52)],
      scale: scale ? scale / 1024 : 1,
    });
  }
  return placements;
}

/**
 * Matrix taking a model vertex (z up) to placement space, as checked against the
 * MODF bounding boxes: rotate by Z(rot.y) Y(rot.x) X(rot.z), then x -> map y, y -> map x.
 * Returns a function giving [mapX, mapY, height].
 */
export function placementTransform(placement: WmoPlacement): (x: number, y: number, z: number) => [number, number, number] {
  const rad = Math.PI / 180;
  const [a, b, c] = [placement.rotation[1] * rad, placement.rotation[0] * rad, placement.rotation[2] * rad];
  const [ca, sa, cb, sb, cc, sc] = [Math.cos(a), Math.sin(a), Math.cos(b), Math.sin(b), Math.cos(c), Math.sin(c)];
  // R = Rz(a) * Ry(b) * Rx(c)
  const m = [
    ca * cb, ca * sb * sc - sa * cc, ca * sb * cc + sa * sc,
    sa * cb, sa * sb * sc + ca * cc, sa * sb * cc - ca * sc,
    -sb, cb * sc, cb * cc,
  ];
  const [px, py, pz] = placement.position;
  const s = placement.scale;
  return (x, y, z) => {
    const wx = (m[0] * x + m[1] * y + m[2] * z) * s;
    const wy = (m[3] * x + m[4] * y + m[5] * z) * s;
    const wz = (m[6] * x + m[7] * y + m[8] * z) * s;
    return [px + wy, pz + wx, py + wz];
  };
}
