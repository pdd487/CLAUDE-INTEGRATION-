import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const SECTIONS = [
  { key: '01', dir: '01_core_dumps', label: 'Core Dumps' },
  { key: '02', dir: '02_raw_data', label: 'Raw Data' },
  { key: '03', dir: '03_sandbox', label: 'Sandbox' },
  { key: '04', dir: '04_synthesis', label: 'Synthesis' },
];

/** Repo root = nearest ancestor of this file that holds a package.json. */
export function repoRoot() {
  let dir = path.dirname(fileURLToPath(import.meta.url));
  for (;;) {
    if (fs.existsSync(path.join(dir, 'package.json'))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) return process.cwd();
    dir = parent;
  }
}

/** Vault root: --vault flag > SANDBOX_VAULT env > <repo>/vault. */
export function resolveVault(flagValue) {
  const raw = flagValue || process.env.SANDBOX_VAULT || path.join(repoRoot(), 'vault');
  return path.resolve(raw);
}

/** Accept "01", "1", "core", "core_dumps", "01_core_dumps" ... */
export function resolveSection(input) {
  if (!input) return SECTIONS[0];
  const needle = String(input).trim().toLowerCase().replace(/[\s-]+/g, '_');
  return (
    SECTIONS.find((s) => s.dir === needle) ||
    SECTIONS.find((s) => s.key === needle.padStart(2, '0')) ||
    SECTIONS.find((s) => s.dir.slice(3).startsWith(needle.replace(/^\d+_/, ''))) ||
    null
  );
}

export function sectionOf(relPath) {
  const top = relPath.split(path.sep)[0];
  return SECTIONS.find((s) => s.dir === top) || null;
}

/** Every indexable markdown file under the vault (templates and INDEX excluded). */
export function walkMarkdown(vaultDir) {
  const out = [];
  const walk = (dir) => {
    let entries;
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const entry of entries.sort((a, b) => a.name.localeCompare(b.name))) {
      if (entry.name.startsWith('.')) continue;
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        walk(full);
      } else if (entry.name.endsWith('.md')) {
        if (entry.name.startsWith('template_') || entry.name === 'INDEX.md') continue;
        out.push(full);
      }
    }
  };
  walk(vaultDir);
  return out;
}

export function ensureVault(vaultDir) {
  for (const section of SECTIONS) {
    fs.mkdirSync(path.join(vaultDir, section.dir), { recursive: true });
  }
  return vaultDir;
}
