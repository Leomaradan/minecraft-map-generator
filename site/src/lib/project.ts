import type { BitDepth } from './heightMapping';

export type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
export type JsonObject = { [key: string]: Json };

export interface Raster {
  width: number;
  height: number;
  bitDepth: BitDepth;
  data: Uint16Array;
}

export type Fluid = 'water' | 'lava';
/** lake: flat at the lowest bank; river: smooth slope between the banks; raw: per-pixel levels kept from an import. */
export type FluidMode = 'lake' | 'river' | 'raw';

export interface FluidBody {
  id: number;
  name: string;
  fluid: Fluid;
  mode: FluidMode;
  /** Surface Y that replaces the computed level (lake and river). */
  levelOverride?: number;
  /** Blocks added to the computed level. */
  offset: number;
}

export interface ReferenceImage {
  id: number;
  name: string;
  bytes: Uint8Array;
  mime: string;
  opacity: number;
  /** Shown on the map. Editor only. */
  visible: boolean;
}

export interface BiomeEntry {
  id: number;
  /** Hidden on the map while editing. Editor only: the exported maps are unchanged. */
  hidden?: boolean;
  /** "#RRGGBB" */
  color: string;
  /** The biomes.json entry in object form (has a "biome" key). */
  data: JsonObject;
}

export type Rotation = 'none' | 'clockwise_90' | 'clockwise_180' | 'counterclockwise_90';
export type Mirror = 'none' | 'left_right' | 'front_back';

export interface StructureTemplate {
  id: number;
  name: string;
  kind: 'template' | 'structure';
  /** Path of the .nbt in the ZIP, for kind "template". */
  path?: string;
  /** Footprint (x, y, z) read from the .nbt. */
  size?: [number, number, number];
  structureId?: string;
  fallbackId?: string;
  rotation: Rotation;
  mirror: Mirror;
  /** A world Y, or "surface". */
  y: number | 'surface';
  yOffset: number;
  entities: boolean;
}

export interface StructureInstance {
  id: number;
  templateId: number;
  x: number;
  z: number;
  /** Fields of the template replaced for this instance. */
  overrides: Partial<Pick<StructureTemplate, 'rotation' | 'mirror' | 'y' | 'yOffset' | 'entities'>>;
}

export interface Project {
  /** map.json without "files". */
  config: JsonObject;
  heightmap: Raster | null;
  /** Original bytes of the heightmap, re-exported untouched. */
  heightmapBytes: Uint8Array | null;
  references: ReferenceImage[];
  fluidLabels: Uint16Array;
  fluidRaw: Uint16Array;
  bodies: FluidBody[];
  biomeIndex: Uint16Array;
  biomes: BiomeEntry[];
  defaultBiomeId: number | null;
  templates: StructureTemplate[];
  instances: StructureInstance[];
  nbtFiles: Record<string, Uint8Array>;
  /** Other ZIP entries (datapack/...) kept as they are. */
  passthrough: Record<string, Uint8Array>;
  nextId: number;
}

export function emptyProject(): Project {
  return {
    config: { format: 1, name: 'My Map' },
    heightmap: null,
    heightmapBytes: null,
    references: [],
    fluidLabels: new Uint16Array(0),
    fluidRaw: new Uint16Array(0),
    bodies: [],
    biomeIndex: new Uint16Array(0),
    biomes: [],
    defaultBiomeId: null,
    templates: [],
    instances: [],
    nbtFiles: {},
    passthrough: {},
    nextId: 1,
  };
}
