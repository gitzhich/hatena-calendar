/**
 * カレンダーチップの色。意味は持たない（NFR-08）。
 * 同じイベント名なら同じ色になるよう、名前のハッシュで割り当てる。
 */
export const CHIP_CLASSES = [
  "bg-chip-1 text-chip-1-ink",
  "bg-chip-2 text-chip-2-ink",
  "bg-chip-3 text-chip-3-ink",
  "bg-chip-4 text-chip-4-ink",
  "bg-chip-5 text-chip-5-ink",
  "bg-chip-6 text-chip-6-ink",
] as const;

export const CHIP_COUNT = CHIP_CLASSES.length;

export function chipIndex(eventName: string): number {
  let hash = 0;
  for (let i = 0; i < eventName.length; i++) {
    hash = (hash * 31 + eventName.charCodeAt(i)) | 0;
  }
  return Math.abs(hash) % CHIP_COUNT;
}

export function chipClass(eventName: string): string {
  return CHIP_CLASSES[chipIndex(eventName)];
}
