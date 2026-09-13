import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

import { buildIndex } from '../lib/build-index.js';
import { findOverlaps, loadEntries, pageCoverage, parseNotebookRef, parseTags, slugify } from '../lib/entries.js';
import { logEntry, renderEntry, targetFile, today } from '../lib/log.js';
import { ensureVault, resolveSection } from '../lib/paths.js';

const CLI = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'notebook.js');

function tempVault() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'root-sandbox-'));
  return ensureVault(dir);
}

function run(vault, args) {
  return execFileSync(process.execPath, [CLI, ...args, '--vault', vault], { encoding: 'utf8' });
}

test('parseNotebookRef reads ids, single pages and ranges', () => {
  assert.deepEqual(parseNotebookRef('NB-03 p.41-42'), { id: 'NB-03', start: 41, end: 42, raw: 'NB-03 p.41-42' });
  assert.equal(parseNotebookRef('NB-03 p.41').end, 41);
  assert.equal(parseNotebookRef('NB-03 pp. 7 - 9').start, 7);
  assert.equal(parseNotebookRef('Green Field Book p.12').id, 'Green Field Book');
  assert.equal(parseNotebookRef('NB-03').start, null);
  assert.equal(parseNotebookRef('  '), null);
  assert.deepEqual(
    [parseNotebookRef('NB-03 p.9-4').start, parseNotebookRef('NB-03 p.9-4').end],
    [4, 9],
    'reversed ranges are normalised',
  );
});

test('parseTags normalises separators and leading hashes', () => {
  assert.deepEqual(parseTags('#Alarm, spindle  #Coolant'), ['alarm', 'spindle', 'coolant']);
  assert.deepEqual(parseTags(''), []);
});

test('slugify matches markdown heading anchors', () => {
  assert.equal(slugify('07:15 — Spindle alarm on BROTHER-01'), '0715--spindle-alarm-on-brother-01');
});

test('an entry round-trips through the parser', () => {
  const vault = tempVault();
  logEntry(vault, {
    title: 'Spindle alarm',
    notebook: 'NB-03',
    pages: '41-42',
    machine: 'BROTHER-01',
    status: 'open',
    tags: '#alarm, spindle',
    body: 'Reseated the encoder cable. Watch it #followup.',
    date: '2026-09-10',
    time: '14:32',
  });

  const [entry] = loadEntries(vault);
  assert.equal(entry.title, 'Spindle alarm');
  assert.equal(entry.date, '2026-09-10');
  assert.equal(entry.time, '14:32');
  assert.equal(entry.machine, 'BROTHER-01');
  assert.equal(entry.status, 'open');
  assert.deepEqual(entry.notebook, { id: 'NB-03', start: 41, end: 42, raw: 'NB-03 p.41-42' });
  assert.deepEqual(entry.tags, ['alarm', 'followup', 'spindle'], 'body tags join the meta tags');
  assert.match(entry.body, /Reseated the encoder cable/);
  assert.equal(entry.file, '01_core_dumps/2026-09-10_dump.md');
});

test('entries append to the same daily dump instead of overwriting it', () => {
  const vault = tempVault();
  logEntry(vault, { title: 'First', date: '2026-09-11', time: '08:00' });
  logEntry(vault, { title: 'Second', date: '2026-09-11', time: '09:00' });

  const entries = loadEntries(vault);
  assert.equal(entries.length, 2);
  assert.deepEqual(entries.map((e) => e.title), ['Second', 'First'], 'newest first');
  const file = fs.readFileSync(path.join(vault, '01_core_dumps', '2026-09-11_dump.md'), 'utf8');
  assert.equal(file.match(/^# 2026-09-11/gm).length, 1, 'one file header');
});

test('a title is required', () => {
  const vault = tempVault();
  assert.throws(() => logEntry(vault, { title: '   ' }), /needs a --title/);
});

test('re-logging the same notebook pages is reported as an overlap', () => {
  const vault = tempVault();
  logEntry(vault, { title: 'Pages 10-12', notebook: 'NB-01 p.10-12', date: '2026-09-01' });
  const clash = logEntry(vault, { title: 'Page 11 again', notebook: 'NB-01 p.11', date: '2026-09-02' });
  assert.equal(clash.overlaps.length, 1);
  assert.equal(clash.overlaps[0].title, 'Pages 10-12');

  const clean = logEntry(vault, { title: 'Page 30', notebook: 'NB-01 p.30', date: '2026-09-03' });
  assert.equal(clean.overlaps.length, 0);
  assert.equal(findOverlaps(loadEntries(vault), 'NB-02', 11, 11).length, 0, 'scoped per notebook');
});

test('page coverage collapses ranges and finds the holes', () => {
  const entries = [
    { notebook: { id: 'NB-01', start: 1, end: 3 } },
    { notebook: { id: 'NB-01', start: 4, end: 4 } },
    { notebook: { id: 'NB-01', start: 9, end: 10 } },
    { notebook: null },
  ];
  const { covered, gaps, pages } = pageCoverage(entries);
  assert.deepEqual(covered, [{ start: 1, end: 4 }, { start: 9, end: 10 }]);
  assert.deepEqual(gaps, [{ start: 5, end: 8 }]);
  assert.equal(pages.length, 6);
});

test('sections and target files resolve as documented', () => {
  assert.equal(resolveSection('02').dir, '02_raw_data');
  assert.equal(resolveSection('sandbox').dir, '03_sandbox');
  assert.equal(resolveSection(undefined).dir, '01_core_dumps');
  assert.equal(resolveSection('nope'), null);

  const vault = '/vault';
  assert.equal(
    targetFile(vault, { section: resolveSection('01'), title: 'x', date: '2026-01-02' }),
    path.join(vault, '01_core_dumps', '2026-01-02_dump.md'),
  );
  assert.equal(
    targetFile(vault, { section: resolveSection('03'), title: 'Wild Idea', date: '2026-01-02' }),
    path.join(vault, '03_sandbox', 'wild-idea.md'),
  );
  assert.equal(
    targetFile(vault, { section: resolveSection('02'), file: 'roster', title: 'x', date: '2026-01-02' }),
    path.join(vault, '02_raw_data', 'roster.md'),
  );
});

test('non-core-dump sections write to their own file', () => {
  const vault = tempVault();
  const result = logEntry(vault, { title: 'Coolant idea', section: '03', date: '2026-09-12', body: 'try it' });
  assert.equal(result.relFile, '03_sandbox/coolant-idea.md');
  assert.equal(loadEntries(vault)[0].sectionLabel, 'Sandbox');
});

test('renderEntry emits parseable markdown without a body', () => {
  const block = renderEntry({ time: '08:00', title: 'T', notebook: parseNotebookRef('NB-01 p.5'), tags: ['a'] });
  assert.match(block, /^## 08:00 — T$/m);
  assert.match(block, /^- \*\*Notebook:\*\* NB-01 p\.5$/m);
  assert.match(block, /no detail captured/);
});

test('the index links every entry and reports notebook gaps', () => {
  const vault = tempVault();
  logEntry(vault, { title: 'Alpha', notebook: 'NB-01 p.1-2', machine: 'BROTHER-01', tags: 'alarm', status: 'open', date: '2026-09-01' });
  logEntry(vault, { title: 'Beta', notebook: 'NB-01 p.6', machine: 'MAZAK-02', tags: 'coolant', date: '2026-09-02' });

  const { markdown, json } = buildIndex(vault, { generatedAt: new Date('2026-09-13T12:00:00Z') });
  assert.match(markdown, /\*\*Entries:\*\* 2 \(2 tied to a physical notebook page\)/);
  assert.match(markdown, /## Open items[\s\S]*Alpha/);
  assert.match(markdown, /### NB-01/);
  assert.match(markdown, /\*\*Gaps:\*\* 3-5/);
  assert.match(markdown, /\[Alpha\]\(01_core_dumps\/2026-09-01_dump\.md#/);
  assert.match(markdown, /### BROTHER-01 \(1\)/);
  assert.match(markdown, /### #coolant \(1\)/);
  assert.equal(json.count, 2);
  assert.equal(json.entries[0].notebook.id, 'NB-01');

  assert.ok(fs.existsSync(path.join(vault, 'INDEX.md')));
  assert.ok(fs.existsSync(path.join(vault, 'index.json')));
  assert.equal(loadEntries(vault).length, 2, 'INDEX.md is not indexed back into itself');
});

test('templates are excluded from the index', () => {
  const vault = tempVault();
  fs.writeFileSync(path.join(vault, '01_core_dumps', 'template_dump.md'), '# t\n\n## 09:00 — Template entry\n');
  assert.equal(loadEntries(vault).length, 0);
});

test('CLI: log, search, notebooks and index --check', () => {
  const vault = tempVault();
  run(vault, ['log', 'Way lube low', '--notebook', 'NB-02', '--pages', '14', '--machine', 'MAZAK-02', '--tags', 'lube,pm', '--body', 'Topped off the reservoir.', '--date', '2026-09-05', '--time', '06:30']);
  run(vault, ['log', 'Chip auger jam', '--notebook', 'NB-02 p.20-21', '--machine', 'BROTHER-01', '--status', 'open', '--date', '2026-09-06', '--no-time']);

  const search = run(vault, ['search', 'auger']);
  assert.match(search, /NB-02 p\.20-21/);
  assert.match(search, /1 entry\./);

  assert.match(run(vault, ['search', '--machine', 'MAZAK-02']), /Way lube low/);
  assert.match(run(vault, ['search', '--tag', '#pm']), /Way lube low/);
  assert.match(run(vault, ['search', '--notebook', 'NB-02', '--page', '21']), /Chip auger jam/);
  assert.match(run(vault, ['search', '--status', 'open']), /Chip auger jam/);
  assert.match(run(vault, ['search', '--since', '2026-09-06']), /Chip auger jam/);

  const notebooks = run(vault, ['notebooks']);
  assert.match(notebooks, /NB-02/);
  assert.match(notebooks, /gaps:\s+15-19/);

  const json = JSON.parse(run(vault, ['search', '--json', '--tag', 'lube']));
  assert.equal(json.length, 1);
  assert.equal(json[0].notebook.start, 14);

  assert.match(run(vault, ['index', '--check']), /up to date/);
  fs.appendFileSync(path.join(vault, '01_core_dumps', '2026-09-06_dump.md'), '\n## 10:00 — Added by hand\n');
  assert.throws(() => run(vault, ['index', '--check']), /out of date|Command failed/);
  run(vault, ['index']);
  assert.match(run(vault, ['index', '--check']), /up to date/);
});

test('CLI: --dry-run prints without writing', () => {
  const vault = tempVault();
  const out = run(vault, ['log', 'Preview only', '--notebook', 'NB-09', '--pages', '3', '--tags', 'x', '--dry-run']);
  assert.match(out, /## .*Preview only/);
  assert.match(out, /- \*\*Notebook:\*\* NB-09 p\.3/);
  assert.equal(loadEntries(vault).length, 0);
});

test('today() formats the local date', () => {
  assert.match(today(new Date(2026, 8, 3)), /^2026-09-03$/);
});
