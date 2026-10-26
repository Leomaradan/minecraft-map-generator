/**
 * The encoding file: maps content keys (MD5 of the decoded file, used by root)
 * to encoding keys (used by the indices). Only the CKey -> EKey table is read.
 */
export class EncodingTable {
  private readonly data: Buffer;
  private readonly cKeySize: number;
  private readonly eKeySize: number;
  private readonly pageSize: number;
  private readonly pageCount: number;
  private readonly headersStart: number;
  private readonly pagesStart: number;

  constructor(data: Buffer) {
    if (data.toString('latin1', 0, 2) !== 'EN') throw new Error('invalid encoding file magic');
    if (data[2] !== 1) throw new Error(`unsupported encoding file version ${data[2]}`);
    this.data = data;
    this.cKeySize = data[3];
    this.eKeySize = data[4];
    this.pageSize = data.readUInt16BE(5) * 1024;
    this.pageCount = data.readUInt32BE(9);
    const especSize = data.readUInt32BE(18);
    this.headersStart = 22 + especSize;
    this.pagesStart = this.headersStart + this.pageCount * (this.cKeySize + 16);
  }

  findEKeys(cKey: Buffer): Buffer[] {
    const page = this.findPage(cKey);
    if (page < 0) return [];
    const { data, cKeySize, eKeySize } = this;
    let pos = this.pagesStart + page * this.pageSize;
    const end = pos + this.pageSize;
    while (pos < end) {
      const keyCount = data[pos];
      if (keyCount === 0) break;
      const keyPos = pos + 6;
      if (cKey.compare(data, keyPos, keyPos + cKeySize, 0, cKeySize) === 0) {
        const keys: Buffer[] = [];
        for (let i = 0; i < keyCount; i++) {
          const start = keyPos + cKeySize + i * eKeySize;
          keys.push(data.subarray(start, start + eKeySize));
        }
        return keys;
      }
      pos = keyPos + cKeySize + keyCount * eKeySize;
    }
    return [];
  }

  /** Index of the last page whose first key is <= cKey. */
  private findPage(cKey: Buffer): number {
    const stride = this.cKeySize + 16;
    let low = 0;
    let high = this.pageCount - 1;
    let found = -1;
    while (low <= high) {
      const mid = (low + high) >>> 1;
      const pos = this.headersStart + mid * stride;
      if (cKey.compare(this.data, pos, pos + this.cKeySize, 0, this.cKeySize) >= 0) {
        found = mid;
        low = mid + 1;
      } else {
        high = mid - 1;
      }
    }
    return found;
  }
}
