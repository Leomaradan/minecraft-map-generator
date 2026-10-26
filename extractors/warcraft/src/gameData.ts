import type { CascStorage } from './casc/storage.ts';
import { getLayout } from './formats/dbd.ts';
import { db2LayoutHash, readDb2, type Db2Record } from './formats/db2.ts';

// FileDataIDs of the DB2 tables we read (stable across builds).
const TABLES = {
  Map: 1349477,
  AreaTable: 1353545,
  LiquidType: 1371380,
  LiquidMaterial: 1132538,
} as const;

export interface MapInfo {
  id: number;
  directory: string;
  name: string;
  wdtFileDataId: number;
  instanceType: number;
  expansion: number;
}

export interface AreaInfo {
  id: number;
  /** Internal English name, e.g. `ElwynnForest`. */
  zoneName: string;
  /** Name in the game's locale. */
  name: string;
  parentId: number;
  mapId: number;
}

export type FluidKind = 'water' | 'ocean' | 'lava' | 'slime';

export interface LiquidInfo {
  kind: FluidKind;
  /** Default liquid vertex format of the liquid's material. */
  format: number;
}

export class GameData {
  readonly maps: Map<number, MapInfo>;
  readonly areas: Map<number, AreaInfo>;
  private readonly liquids: Map<number, LiquidInfo>;

  private constructor(maps: Map<number, MapInfo>, areas: Map<number, AreaInfo>, liquids: Map<number, LiquidInfo>) {
    this.maps = maps;
    this.areas = areas;
    this.liquids = liquids;
  }

  static async load(storage: CascStorage, cacheDir?: string): Promise<GameData> {
    const read = async (name: keyof typeof TABLES) => {
      const data = storage.readFile(TABLES[name], true);
      if (!data) throw new Error(`${name}.db2 is not in local storage`);
      return readDb2(data, await getLayout(name, db2LayoutHash(data), cacheDir));
    };

    const maps = new Map<number, MapInfo>();
    for (const r of (await read('Map')).values()) {
      maps.set(num(r.ID), {
        id: num(r.ID),
        directory: str(r.Directory),
        name: str(r.MapName_lang),
        wdtFileDataId: num(r.WdtFileDataID),
        instanceType: num(r.InstanceType),
        expansion: num(r.ExpansionID),
      });
    }

    const areas = new Map<number, AreaInfo>();
    for (const r of (await read('AreaTable')).values()) {
      areas.set(num(r.ID), {
        id: num(r.ID),
        zoneName: str(r.ZoneName),
        name: str(r.AreaName_lang),
        parentId: num(r.ParentAreaID),
        mapId: num(r.ContinentID),
      });
    }

    const materials = await read('LiquidMaterial');
    const liquids = new Map<number, LiquidInfo>();
    const kinds: FluidKind[] = ['water', 'ocean', 'lava', 'slime'];
    for (const r of (await read('LiquidType')).values()) {
      const material = materials.get(num(r.MaterialID));
      const kind = liquidKind(str(r.Name)) ?? kinds[num(r.SoundBank)] ?? 'water';
      // Ocean layers only store depths: their surface is flat whatever the material says.
      liquids.set(num(r.ID), { kind, format: kind === 'ocean' ? 2 : material ? num(material.LVF) : 0 });
    }

    return new GameData(maps, areas, liquids);
  }

  liquid(liquidTypeId: number): LiquidInfo {
    return this.liquids.get(liquidTypeId) ?? { kind: 'water', format: 0 };
  }

  /** Finds a map by ID, directory or name (case-insensitive). */
  findMap(query: string): MapInfo | undefined {
    const q = query.toLowerCase();
    const all = [...this.maps.values()];
    // IDs first: many recent maps have no directory but their ID.
    return (
      (/^\d+$/.test(q) ? this.maps.get(Number(q)) : undefined) ??
      all.find((m) => m.directory.toLowerCase() === q) ??
      all.find((m) => m.name.toLowerCase() === q)
    );
  }

  /** Finds areas by ID, internal name or localized name (case-insensitive), optionally on one map. */
  findAreas(query: string, mapId?: number): AreaInfo[] {
    const q = query.toLowerCase();
    if (/^\d+$/.test(q)) {
      const area = this.areas.get(Number(q));
      return area && (mapId === undefined || area.mapId === mapId) ? [area] : [];
    }
    return [...this.areas.values()].filter(
      (a) => (mapId === undefined || a.mapId === mapId) && (a.zoneName.toLowerCase() === q || a.name.toLowerCase() === q),
    );
  }

  /** The area and its parents, from the area itself up to the zone. */
  areaChain(areaId: number): AreaInfo[] {
    const chain: AreaInfo[] = [];
    let area = this.areas.get(areaId);
    while (area && !chain.includes(area)) {
      chain.push(area);
      area = area.parentId ? this.areas.get(area.parentId) : undefined;
    }
    return chain;
  }
}

/** Kind of liquid from its internal name, when the name tells. */
function liquidKind(name: string): FluidKind | undefined {
  const n = name.toLowerCase();
  if (/lava|magma|fire/.test(n)) return 'lava';
  if (/slime|ooze|sludge/.test(n)) return 'slime';
  if (/ocean|sea\b/.test(n)) return 'ocean';
  if (/water|river|lake|swamp|pool|bog|marsh/.test(n)) return 'water';
  return undefined;
}

function num(value: Db2Record[string] | undefined): number {
  return typeof value === 'number' ? value : 0;
}

function str(value: Db2Record[string] | undefined): string {
  return typeof value === 'string' ? value : '';
}
