import type { AreaInfo } from './gameData.ts';

/** An entry of the mod's biomes.json: a biome id, or an object with surface blocks. */
export type BiomeEntry = string | { biome: string; [key: string]: unknown };

const ASH = {
  top: { blocks: ['minecraft:blackstone', 'minecraft:basalt', { block: 'minecraft:coarse_dirt', weight: 2 }], method: 'perlin', scale: 10 },
  filler: 'minecraft:blackstone',
};

/** Biomes of the zones of the main continents, by internal zone name (lower case). */
const ZONES: Record<string, BiomeEntry> = {
  // Eastern Kingdoms
  dunmorogh: 'snowy_taiga', ironforge: 'snowy_slopes', lochmodan: 'meadow', badlands: 'badlands',
  blastedlands: 'badlands', blastedlandsz: 'badlands', swampofsorrows: 'swamp', duskwood: 'dark_forest',
  wetlands: 'swamp', elwynnforest: 'forest', stormwindcity: 'plains', westfall: 'plains', redridgemountains: 'windswept_forest',
  deadwindpass: 'windswept_gravelly_hills', stranglethornvale: 'jungle', "gillijim'sisle": 'jungle',
  burningsteppes: { biome: 'minecraft:badlands', ...ASH }, searinggorge: { biome: 'minecraft:badlands', ...ASH },
  blackrockmountain: { biome: 'minecraft:badlands', ...ASH }, arathihighlands: 'windswept_hills', dunalgaz: 'windswept_hills',
  thehinterlands: 'forest', hillsbradfoothills: 'meadow', silverpineforest: 'old_growth_pine_taiga',
  tirisfalglades: 'pale_garden', tirisfalgladespostblight: 'pale_garden', undercity: 'pale_garden',
  westernplaguelands: 'plains', easternplaguelands: 'pale_garden', lordamerelake: 'river', twilighthighlands: 'windswept_hills',
  vashjir: 'warm_ocean', ruinsofgilneas: 'old_growth_spruce_taiga', ruinsofgilneascity: 'old_growth_spruce_taiga',
  quelthalas: 'old_growth_birch_forest', eversongwoods: 'old_growth_birch_forest', '12eversongwoodseasternkingdoms': 'old_growth_birch_forest',
  ghostlands: 'pale_garden', zulaman: 'sparse_jungle', silvermooncity: 'plains', '12silvermooneasternkingdoms': 'plains',
  isleofqueldanas: 'forest', '12isleofqueldanas': 'forest', kultiras: 'forest',
  // Kalimdor
  durotar: 'badlands', orgrimmar: 'badlands', thebarrens: 'savanna', southernbarrens: 'savanna', mulgore: 'plains',
  thunderbluff: 'plains', dustwallow: 'mangrove_swamp', azshara: 'cherry_grove', teldrassil: 'forest', darnassus: 'forest',
  darkshore: 'taiga', ashenvale: 'dark_forest', feralas: 'jungle', felwood: 'dark_forest', thousandneedles: 'badlands',
  desolace: 'windswept_gravelly_hills', stonetalonmountains: 'windswept_hills', tanaris: 'desert', ungorocrater: 'jungle',
  moonglade: 'flower_forest', hyjal: 'taiga', winterspring: 'snowy_taiga', silithus: 'desert', uldum: 'desert',
  cavernsoftime: 'desert', "gatesofahn'qiraj": 'desert', ahnqirajthefallenkingdom: 'desert', thelostisles01: 'jungle',
  southfuryriver: 'river', theveiledsea: 'ocean',
  // Outland
  hellfire: 'badlands', nagrand: 'meadow', terokkarforest: 'old_growth_pine_taiga',
  shadowmoonvalley: { biome: 'minecraft:badlands', ...ASH }, zangarmarsh: 'mushroom_fields', bladesedgemountains: 'eroded_badlands',
  netherstorm: 'badlands', azuremystisle: 'forest', bloodmystisle: 'wooded_badlands', theexodar: 'plains',
  shattrathcity: 'plains', auchindoun: 'desert',
  // Northrend
  dragonblight: 'snowy_plains', zuldrak: 'snowy_plains', thestormpeaks: 'frozen_peaks', icecrown: 'snowy_slopes',
  grizzlyhills: 'old_growth_spruce_taiga', howlingfjord: 'old_growth_pine_taiga', crystalsongforest: 'snowy_taiga',
  boreantundra: 'snowy_plains', sholazarbasin: 'jungle', thefrozensea: 'frozen_ocean', wintergrasp: 'snowy_plains',
  hrothgarslanding: 'snowy_plains', thefrozenthrone: 'frozen_peaks', dalaran: 'plains',
  // Pandaria
  thejadeforest: 'bamboo_jungle', valleyofthefourwinds: 'sunflower_plains', valeofeternalblossoms: 'cherry_grove',
  kunlaisummit: 'snowy_slopes', townlongwastes: 'savanna_plateau', theveiledstair: 'forest', krasarang: 'sparse_jungle',
  dreadwastes: 'badlands', isleofgiants: 'jungle', timelessisle: 'forest',
  // Draenor
  talador: 'birch_forest', shadowmoonvalleydr: 'dark_forest', frostfireridge: 'snowy_slopes', gorgrond: 'jungle',
  spiresofarak: 'old_growth_spruce_taiga', tanaanjungle: 'jungle', nagranddraenor: 'meadow', ashran: 'plains',
  // Broken Isles
  azsuna: 'cherry_grove', highmountain: 'windswept_forest', stormheim: 'old_growth_spruce_taiga',
  brokenshore: 'badlands', valsharah: 'dark_forest', suramar: 'forest', thegreatsealegion: 'ocean',
  // Kul Tiras and Zandalar
  tiragarde: 'forest', drustvar: 'pale_garden', stormsongvalley: 'meadow', mechagonisland: 'stony_shore',
  zuldazar: 'jungle', nazmir: 'mangrove_swamp', voldun: 'desert',
  // Shadowlands
  revendreth: 'dark_forest', maldraxxus: 'badlands', ardenweald: 'flower_forest', bastionvignettes: 'meadow',
  bastiontreasures: 'meadow', oribos: 'plains', mawmaxlevel: { biome: 'minecraft:badlands', ...ASH },
  themawintro: { biome: 'minecraft:badlands', ...ASH }, korthia: { biome: 'minecraft:badlands', ...ASH },
  // Dragon Isles
  thewakingshores: 'windswept_forest', ohnahranplains: 'plains', dragonisleszone3: 'snowy_taiga',
  thaldraszus: 'old_growth_birch_forest', theforbiddenreachdragonisles: 'windswept_hills', amirdrassildragonisles: 'flower_forest',
  // Khaz Algar
  '11zone1': 'windswept_hills', dornogal: 'plains', '11zone2': 'dripstone_caves', azjkahet: 'dark_forest',
  '11zone3': 'sunflower_plains',
};

/** Fallbacks on words of the internal area name. */
const KEYWORDS: [RegExp, string][] = [
  [/frozen|frost|snow|ice|winter|glacier/, 'snowy_taiga'],
  [/desert|sand|dune/, 'desert'],
  [/swamp|marsh|bog|mire|fen/, 'swamp'],
  [/jungle/, 'jungle'],
  [/beach|shore|coast|cove/, 'beach'],
  [/plague|blight|scourge|grave|crypt|necro/, 'pale_garden'],
  [/volcan|molten|burning|ash|fire|cinder/, 'badlands'],
  [/barren|waste|steppe/, 'savanna'],
  [/forest|wood|grove|glade/, 'forest'],
  [/peak|mountain|summit|ridge|cliff|crag/, 'windswept_hills'],
  [/plain|field|meadow|farm/, 'plains'],
  [/sea|ocean/, 'ocean'],
  [/lake|river|falls/, 'river'],
];

export const DEFAULT_BIOME = 'minecraft:plains';

/**
 * Picks the biome of an area: the user's overrides, then the zone table, then
 * keywords of the names, from the area up to its zone.
 */
export function biomeForArea(chain: AreaInfo[], overrides: Record<string, BiomeEntry> = {}): BiomeEntry {
  const lookup = (table: Record<string, BiomeEntry>) => {
    for (const area of chain) {
      const entry = table[String(area.id)] ?? table[area.zoneName.toLowerCase()];
      if (entry !== undefined) return entry;
    }
    return undefined;
  };
  const lowerOverrides = Object.fromEntries(Object.entries(overrides).map(([k, v]) => [k.toLowerCase(), v]));
  const entry =
    lookup(lowerOverrides) ??
    lookup(ZONES) ??
    chain.map((a) => KEYWORDS.find(([re]) => re.test(a.zoneName.toLowerCase()))?.[1]).find((b) => b !== undefined) ??
    DEFAULT_BIOME;
  return normalize(entry);
}

function normalize(entry: BiomeEntry): BiomeEntry {
  const id = (b: string) => (b.includes(':') ? b : `minecraft:${b}`);
  return typeof entry === 'string' ? id(entry) : { ...entry, biome: id(entry.biome) };
}

export function biomeId(entry: BiomeEntry): string {
  return typeof entry === 'string' ? entry : entry.biome;
}

export type Climate = 'frozen' | 'warm' | 'temperate';

/** Climate of a land biome, used to pick its oceans and rivers. */
export function climateOf(entry: BiomeEntry): Climate {
  const id = biomeId(entry);
  if (/snowy|frozen|ice|jagged|(^|:)grove$/.test(id)) return 'frozen';
  if (/desert|jungle|badlands|savanna|mangrove|warm/.test(id)) return 'warm';
  return 'temperate';
}

/** Biome of open water, from the climate around it. */
export function waterBiome(kind: 'ocean' | 'river', climate: Climate, deep: boolean): string {
  if (kind === 'river') return climate === 'frozen' ? 'minecraft:frozen_river' : 'minecraft:river';
  if (climate === 'frozen') return deep ? 'minecraft:deep_frozen_ocean' : 'minecraft:frozen_ocean';
  if (climate === 'warm') return deep ? 'minecraft:deep_lukewarm_ocean' : 'minecraft:warm_ocean';
  return deep ? 'minecraft:deep_ocean' : 'minecraft:ocean';
}
