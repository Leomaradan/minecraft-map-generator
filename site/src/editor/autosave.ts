const DB = 'mapgen-editor';
const STORE = 'projects';
const KEY = 'autosave';

function open(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB, 1);
    req.onupgradeneeded = () => req.result.createObjectStore(STORE);
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function run<T>(mode: IDBTransactionMode, fn: (s: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const db = await open();
  return new Promise((resolve, reject) => {
    const req = fn(db.transaction(STORE, mode).objectStore(STORE));
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

export async function saveDraft(zip: Uint8Array): Promise<void> {
  try {
    await run('readwrite', (s) => s.put(zip, KEY));
  } catch {
    // Storage can be unavailable (private mode, quota): the draft is a convenience only.
  }
}

export async function loadDraft(): Promise<Uint8Array | null> {
  try {
    return ((await run('readonly', (s) => s.get(KEY))) as Uint8Array | undefined) ?? null;
  } catch {
    return null;
  }
}

export async function clearDraft(): Promise<void> {
  try {
    await run('readwrite', (s) => s.delete(KEY));
  } catch {
    // ignored, see saveDraft
  }
}
