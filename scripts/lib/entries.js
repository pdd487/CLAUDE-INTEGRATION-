import fs from 'node:fs';
import path from 'node:path';
import { sectionOf, walkMarkdown } from './paths.js';

const HEADING_RE = /^##\s+(?:(\d{1,2}:\d{2})\s*[—–-]\s*)?(.+?)\s*$/;
// "- **Notebook:** NB-03 p.41-42"  (colon inside or outside the bold both work)
const META_RE = /^[-*]\s+\*\*([^*:]+?):?\*\*:?\s*(.*)$/;
const TAG_RE = /(?:^|[\s(,;[])#([A-Za-z0-9][\w/-]*)/g;
const DATE_IN_NAME_RE = /(\d{4}-\d{2}-\d{2})/;

/** "NB-03 p.41-42" -> { id: 'NB-03', start: 41, end: 42, raw } */
export function parseNotebookRef(value) {
  const raw = String(value || '').trim();
  if (!raw) return null;
  const pages = raw.match(/\bpp?\.?\s*(\d+)\s*(?:[-–—]|to|thru)?\s*(\d+)?\s*$/i);
  let id = raw;
  let start = null;
  let end = null;
  if (pages) {
    id = raw.slice(0, pages.index).trim();
    start = Number(pages[1]);
    end = pages[2] ? Number(pages[2]) : start;
    if (end < start) [start, end] = [end, start];
  }
  id = id.replace(/[,;:]+$/, '').trim();
  if (!id) return null;
  return { id, start, end, raw };
}

export function formatNotebookRef(ref) {
  if (!ref) return '';
  if (ref.start == null) return ref.id;
  return ref.start === ref.end ? `${ref.id} p.${ref.start}` : `${ref.id} p.${ref.start}-${ref.end}`;
}

export function parseTags(value) {
  return String(value || '')
    .split(/[\s,]+/)
    .map((t) => t.replace(/^#/, '').trim().toLowerCase())
    .filter(Boolean);
}

/** GitHub-flavoured heading anchor, so INDEX.md links land on the entry. */
export function slugify(text) {
  return String(text)
    .toLowerCase()
    .replace(/[^\w\s-]/g, '')
    .trim()
    // GitHub turns each whitespace character into its own hyphen; match that
    // exactly or the INDEX.md links miss their heading.
    .replace(/\s/g, '-');
}

function parseFrontmatter(lines) {
  const data = {};
  if (lines[0] !== '---') return { data, start: 0 };
  for (let i = 1; i < lines.length; i += 1) {
    if (lines[i] === '---') return { data, start: i + 1 };
    const match = lines[i].match(/^([A-Za-z_][\w-]*):\s*(.*)$/);
    if (match) data[match[1].toLowerCase()] = match[2].trim();
  }
  return { data, start: 0 };
}

function collectInlineTags(text) {
  const found = new Set();
  for (const match of String(text).matchAll(TAG_RE)) found.add(match[1].toLowerCase());
  return [...found];
}

/** Parse one markdown file into its "## " entry blocks. */
export function parseFile(fullPath, vaultDir) {
  const relPath = path.relative(vaultDir, fullPath);
  const lines = fs.readFileSync(fullPath, 'utf8').split(/\r?\n/);
  const { data: frontmatter, start } = parseFrontmatter(lines);
  const fileDate = frontmatter.date || (relPath.match(DATE_IN_NAME_RE) || [])[1] || null;
  const section = sectionOf(relPath);

  const blocks = [];
  let current = null;
  for (let i = start; i < lines.length; i += 1) {
    const heading = lines[i].match(HEADING_RE);
    if (heading) {
      if (current) blocks.push(current);
      current = { time: heading[1] || null, title: heading[2], raw: lines[i], line: i + 1, body: [] };
      continue;
    }
    if (current) current.body.push(lines[i]);
  }
  if (current) blocks.push(current);

  return blocks.map((block) => {
    const meta = {};
    let cursor = 0;
    while (cursor < block.body.length) {
      const line = block.body[cursor];
      const match = line.match(META_RE);
      if (match) {
        meta[match[1].trim().toLowerCase()] = match[2].trim();
        cursor += 1;
        continue;
      }
      if (line.trim() === '' && cursor === 0) {
        cursor += 1;
        continue;
      }
      break;
    }
    const body = block.body.slice(cursor).join('\n').trim();
    const tags = new Set([...parseTags(meta.tags), ...collectInlineTags(body)]);
    const known = new Set(['notebook', 'machine', 'tags', 'date', 'time', 'status']);
    const extra = Object.fromEntries(Object.entries(meta).filter(([k]) => !known.has(k)));

    const headingText = block.raw.replace(/^##\s+/, '');
    return {
      file: relPath.split(path.sep).join('/'),
      section: section ? section.dir : null,
      sectionLabel: section ? section.label : 'Unfiled',
      line: block.line,
      date: meta.date || fileDate,
      time: meta.time || block.time,
      title: block.title,
      heading: headingText,
      anchor: slugify(headingText),
      notebook: parseNotebookRef(meta.notebook),
      machine: meta.machine || frontmatter.machine || null,
      status: meta.status || null,
      tags: [...tags].sort(),
      extra,
      body,
    };
  });
}

export function compareEntries(a, b) {
  const dateDiff = String(b.date || '').localeCompare(String(a.date || ''));
  if (dateDiff !== 0) return dateDiff;
  const timeDiff = String(b.time || '').localeCompare(String(a.time || ''));
  if (timeDiff !== 0) return timeDiff;
  return a.file.localeCompare(b.file);
}

/** All entries in the vault, newest first. */
export function loadEntries(vaultDir) {
  const entries = [];
  for (const file of walkMarkdown(vaultDir)) entries.push(...parseFile(file, vaultDir));
  return entries.sort(compareEntries);
}

/** Contiguous page ranges covered for one notebook, plus the holes between them. */
export function pageCoverage(entries) {
  const pages = new Set();
  for (const entry of entries) {
    const ref = entry.notebook;
    if (!ref || ref.start == null) continue;
    for (let page = ref.start; page <= ref.end; page += 1) pages.add(page);
  }
  const sorted = [...pages].sort((a, b) => a - b);
  const covered = [];
  const gaps = [];
  for (const page of sorted) {
    const last = covered[covered.length - 1];
    if (last && page === last.end + 1) last.end = page;
    else covered.push({ start: page, end: page });
  }
  for (let i = 1; i < covered.length; i += 1) {
    gaps.push({ start: covered[i - 1].end + 1, end: covered[i].start - 1 });
  }
  return { pages: sorted, covered, gaps };
}

export function formatRanges(ranges) {
  if (!ranges.length) return '—';
  return ranges.map((r) => (r.start === r.end ? `${r.start}` : `${r.start}-${r.end}`)).join(', ');
}

/** Entries whose page range overlaps [start, end] in the same notebook. */
export function findOverlaps(entries, notebookId, start, end) {
  if (start == null) return [];
  const id = String(notebookId).toLowerCase();
  return entries.filter((entry) => {
    const ref = entry.notebook;
    return (
      ref &&
      ref.start != null &&
      ref.id.toLowerCase() === id &&
      ref.start <= end &&
      ref.end >= start
    );
  });
}
