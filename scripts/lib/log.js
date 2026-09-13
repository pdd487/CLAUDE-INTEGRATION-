import fs from 'node:fs';
import path from 'node:path';
import { resolveSection, SECTIONS } from './paths.js';
import { findOverlaps, formatNotebookRef, loadEntries, parseNotebookRef, parseTags, slugify } from './entries.js';

export function today(now = new Date()) {
  const pad = (n) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

export function nowTime(now = new Date()) {
  const pad = (n) => String(n).padStart(2, '0');
  return `${pad(now.getHours())}:${pad(now.getMinutes())}`;
}

function dumpHeader(vaultDir, date) {
  const template = path.join(vaultDir, '01_core_dumps', 'template_dump.md');
  if (fs.existsSync(template)) {
    return fs.readFileSync(template, 'utf8').replaceAll('{{date}}', date).trimEnd();
  }
  return ['---', `date: ${date}`, 'type: core_dump', '---', '', `# ${date} — Core Dump`].join('\n');
}

function pageHeader(title, date) {
  return ['---', `date: ${date}`, '---', '', `# ${title}`].join('\n');
}

/** Where a new entry lands: daily dump for core dumps, a titled file elsewhere. */
export function targetFile(vaultDir, { section, file, title, date }) {
  if (file) {
    const rel = file.endsWith('.md') ? file : `${file}.md`;
    return rel.includes('/') ? path.join(vaultDir, rel) : path.join(vaultDir, section.dir, rel);
  }
  if (section.dir === '01_core_dumps') {
    return path.join(vaultDir, section.dir, `${date}_dump.md`);
  }
  return path.join(vaultDir, section.dir, `${slugify(title) || 'untitled'}.md`);
}

/** Join a bare notebook id and a --pages range into one reference. */
export function buildNotebookRef(notebook, pages) {
  if (!notebook) return null;
  const raw = pages && !/\bpp?\.?\s*\d/i.test(notebook) ? `${notebook} p.${pages}` : notebook;
  return parseNotebookRef(raw);
}

export function renderEntry({ time, title, notebook, machine, status, tags, body }) {
  const lines = [`## ${time ? `${time} — ` : ''}${title}`];
  if (notebook) lines.push(`- **Notebook:** ${formatNotebookRef(notebook)}`);
  if (machine) lines.push(`- **Machine:** ${machine}`);
  if (status) lines.push(`- **Status:** ${status}`);
  if (tags && tags.length) lines.push(`- **Tags:** ${tags.map((t) => `#${t}`).join(' ')}`);
  lines.push('');
  lines.push(body && body.trim() ? body.trim() : '_(no detail captured — transcribe from the notebook page)_');
  return lines.join('\n');
}

/**
 * Append one entry to the vault. Returns the file written, the rendered block,
 * and any existing entries that already claim those notebook pages.
 */
export function logEntry(vaultDir, options) {
  const section = resolveSection(options.section) || SECTIONS[0];
  const date = options.date || today();
  const time = options.time === '' ? null : options.time || nowTime();
  const title = (options.title || '').trim();
  if (!title) throw new Error('an entry needs a --title');

  const notebook = buildNotebookRef(options.notebook, options.pages);
  if (options.notebook && !notebook) throw new Error(`could not read notebook reference: ${options.notebook}`);

  const tags = parseTags(options.tags);
  const block = renderEntry({ time, title, notebook, machine: options.machine, status: options.status, tags, body: options.body });

  const overlaps = notebook
    ? findOverlaps(loadEntries(vaultDir), notebook.id, notebook.start, notebook.end)
    : [];

  const file = targetFile(vaultDir, { section, file: options.file, title, date });
  fs.mkdirSync(path.dirname(file), { recursive: true });
  if (!fs.existsSync(file)) {
    const header = section.dir === '01_core_dumps' ? dumpHeader(vaultDir, date) : pageHeader(title, date);
    fs.writeFileSync(file, `${header}\n`);
  }
  const existing = fs.readFileSync(file, 'utf8').replace(/\s+$/, '');
  fs.writeFileSync(file, `${existing}\n\n${block}\n`);

  return { file, relFile: path.relative(vaultDir, file).split(path.sep).join('/'), block, overlaps, notebook };
}
