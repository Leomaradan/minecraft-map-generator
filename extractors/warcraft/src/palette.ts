import type { Rgb } from './formats/blp.ts';

/** Full, opaque building blocks with their average texture color. */
const BLOCKS: [string, number][] = [
  ['stone', 0x7d7d7d], ['cobblestone', 0x7f7f7f], ['stone_bricks', 0x7a797a], ['smooth_stone', 0x9e9e9e],
  ['andesite', 0x888889], ['polished_andesite', 0x848786], ['diorite', 0xbcbcbc], ['polished_diorite', 0xc0c1c2],
  ['granite', 0x956755], ['polished_granite', 0x9a6a59], ['tuff', 0x6c6d66], ['tuff_bricks', 0x62675f],
  ['calcite', 0xdfe0dc], ['deepslate_bricks', 0x464647], ['deepslate_tiles', 0x363637], ['cobbled_deepslate', 0x4d4d50],
  ['blackstone', 0x2a2328], ['polished_blackstone_bricks', 0x302a31], ['basalt', 0x515156], ['bricks', 0x966153],
  ['mud_bricks', 0x89674f], ['packed_mud', 0x8e6b50], ['sandstone', 0xd8cb9b], ['smooth_sandstone', 0xdfd6aa],
  ['red_sandstone', 0xb5621f], ['quartz_block', 0xebe5de], ['oak_planks', 0xa2824e], ['spruce_planks', 0x725430],
  ['birch_planks', 0xc0af79], ['jungle_planks', 0xa07350], ['acacia_planks', 0xa85a32], ['dark_oak_planks', 0x422b14],
  ['mangrove_planks', 0x753630], ['cherry_planks', 0xe2b2ac], ['crimson_planks', 0x653046], ['warped_planks', 0x2b6863],
  ['white_terracotta', 0xd1b2a1], ['light_gray_terracotta', 0x876b62], ['gray_terracotta', 0x3a2a24],
  ['black_terracotta', 0x251710], ['brown_terracotta', 0x4d3324], ['red_terracotta', 0x8f3d2f],
  ['orange_terracotta', 0xa25426], ['yellow_terracotta', 0xba8523], ['lime_terracotta', 0x677535],
  ['green_terracotta', 0x4c532a], ['cyan_terracotta', 0x575b5b], ['light_blue_terracotta', 0x716d8a],
  ['blue_terracotta', 0x4a3c5b], ['purple_terracotta', 0x764656], ['magenta_terracotta', 0x95586c],
  ['pink_terracotta', 0xa14e4e], ['terracotta', 0x985e43], ['white_concrete', 0xcfd5d6], ['light_gray_concrete', 0x7d7d73],
  ['gray_concrete', 0x36393d], ['black_concrete', 0x080a0f], ['red_concrete', 0x8e2020], ['orange_concrete', 0xe06100],
  ['yellow_concrete', 0xf0af15], ['lime_concrete', 0x5ea818], ['green_concrete', 0x495b24], ['cyan_concrete', 0x157788],
  ['light_blue_concrete', 0x2389c6], ['blue_concrete', 0x2c2e8f], ['purple_concrete', 0x641f9c],
  ['magenta_concrete', 0xa9309f], ['pink_concrete', 0xd5658e], ['brown_concrete', 0x603b1f],
  ['prismarine_bricks', 0x63ab9e], ['dark_prismarine', 0x335b4b], ['nether_bricks', 0x2c151a], ['red_nether_bricks', 0x450709],
  ['end_stone_bricks', 0xdae0a2], ['purpur_block', 0xa97da9], ['copper_block', 0xc06b4f], ['weathered_copper', 0x6c996e],
  ['oxidized_copper', 0x52a284], ['iron_block', 0xdcdcdc], ['gold_block', 0xf6d03d], ['moss_block', 0x596d2d],
  ['snow_block', 0xf9fefe], ['packed_ice', 0x8db4fa], ['obsidian', 0x0f0a18], ['amethyst_block', 0x8561bf],
  ['bone_block', 0xe5e1cf], ['dirt', 0x866043],
];

export const DEFAULT_BLOCK = 'minecraft:stone_bricks';

const LAB = BLOCKS.map(([name, color]) => ({
  name: `minecraft:${name}`,
  lab: toLab({ r: color >> 16, g: (color >> 8) & 0xff, b: color & 0xff }),
}));

const cache = new Map<number, string>();

/** The block whose color is the closest (CIE Lab distance). */
export function nearestBlock(color: Rgb): string {
  const key = (Math.round(color.r) << 16) | (Math.round(color.g) << 8) | Math.round(color.b);
  let name = cache.get(key);
  if (name === undefined) {
    const lab = toLab(color);
    let best = Infinity;
    name = DEFAULT_BLOCK;
    for (const block of LAB) {
      const d = (block.lab[0] - lab[0]) ** 2 + (block.lab[1] - lab[1]) ** 2 + (block.lab[2] - lab[2]) ** 2;
      if (d < best) {
        best = d;
        name = block.name;
      }
    }
    cache.set(key, name);
  }
  return name;
}

function toLab({ r, g, b }: Rgb): [number, number, number] {
  const linear = (c: number) => {
    const v = c / 255;
    return v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
  };
  const [lr, lg, lb] = [linear(r), linear(g), linear(b)];
  const x = (lr * 0.4124 + lg * 0.3576 + lb * 0.1805) / 0.95047;
  const y = lr * 0.2126 + lg * 0.7152 + lb * 0.0722;
  const z = (lr * 0.0193 + lg * 0.1192 + lb * 0.9505) / 1.08883;
  const f = (t: number) => (t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16 / 116);
  return [116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z))];
}
