#!/usr/bin/env node
/**
 * Root Sandbox — physical notebook logging and index.
 *
 *   node scripts/notebook.js log "Spindle alarm" --notebook NB-03 --pages 41-42 --machine BROTHER-01
 *   node scripts/notebook.js index
 *   node scripts/notebook.js search coolant --machine MAZAK-02
 *   node scripts/notebook.js notebooks
 */
import fs from 'node:fs';
import path from 'node:path';
import { buildIndex } from './lib/build-index.js';
import { formatNotebookRef, formatRanges, loadEntries, pageCoverage, parseNotebookRef, parseTags } from './lib/entries.js';
import { buildNotebookRef, logEntry, renderEntry } from './lib/log.js';
import { ensureVault, resolveVault, SECTIONS } from './lib/paths.js';

const BOOLEAN_FLAGS = new Set(['json', 'help', 'no-time', 'dry-run', 'check', 'full']);

function parseArgs(argv) {
  const flags = {};
  const positional = [];
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (!arg.startsWith('--')) {
      positional.push(arg);
      continue;
    }
    const [name, inline] = arg.slice(2).split(/=(.*)/s);
    if (inline !== undefined) {
      flags[name] = inline;
    } else if (BOOLEAN_FLAGS.has(name)) {
      flags[name] = true;
    } else {
      flags[name] = argv[i + 1] ?? '';
      i += 1;
    }
  }
  return { flags, positional };
}

const HELP = `Root Sandbox — physical notebook logging and index

Usage: node scripts/notebook.js <command> [options]

Commands
  log [title]     Append an entry to the vault (defaults to today's core dump).
  index           Rebuild vault/INDEX.md and vault/index.json.
  search [text]   Find entries by text, tag, machine, notebook or page.
  notebooks       Show every physical notebook, its logged pages and its gaps.
  help            Show this message.

log options
  --title <text>        Entry title (or pass it as the first argument).
  --notebook <ref>      Physical notebook, e.g. "NB-03" or "NB-03 p.41-42".
  --pages <range>       Page or page range when --notebook is just an id.
  --machine <name>      Machine or asset the entry is about.
  --tags <list>         Comma/space separated, with or without a leading '#'.
  --status <state>      e.g. open, done, waiting-on-parts.
  --body <text>         Entry detail. Use "-" to read it from stdin.
  --date <YYYY-MM-DD>   Defaults to today.
  --time <HH:MM>        Defaults to now. --no-time omits the timestamp.
  --section <01..04>    01_core_dumps (default), 02_raw_data, 03_sandbox, 04_synthesis.
  --file <name.md>      Write to a specific file instead of the daily dump.
  --dry-run             Print the entry without writing it.

search options
  --tag <tag>   --machine <name>   --notebook <id>   --page <n>
  --status <s>  --since <date>     --until <date>    --limit <n>   --full
  --json

Global options
  --vault <dir>   Vault location (default: ./vault, or $SANDBOX_VAULT).
  --json          Machine-readable output where supported.
`;

function readStdin() {
  try {
    return fs.readFileSync(0, 'utf8');
  } catch {
    return '';
  }
}

function cmdLog(vaultDir, { flags, positional }) {
  ensureVault(vaultDir);
  const body = flags.body === '-' ? readStdin() : flags.body;
  const options = {
    title: flags.title || positional.join(' '),
    notebook: flags.notebook,
    pages: flags.pages,
    machine: flags.machine,
    tags: flags.tags,
    status: flags.status,
    body,
    date: flags.date,
    time: flags['no-time'] ? '' : flags.time,
    section: flags.section,
    file: flags.file,
  };

  if (flags['dry-run']) {
    const notebook = buildNotebookRef(options.notebook, options.pages);
    process.stdout.write(`${renderEntry({ ...options, notebook, tags: parseTags(options.tags) })}\n`);
    return 0;
  }

  const result = logEntry(vaultDir, options);
  if (result.overlaps.length) {
    console.warn(`! ${formatNotebookRef(result.notebook)} is already logged by:`);
    for (const entry of result.overlaps) {
      console.warn(`    ${entry.date || '????-??-??'}  ${entry.title}  (${entry.file}:${entry.line})`);
    }
  }
  console.log(`Logged to ${result.relFile}`);
  console.log(result.block.split('\n').map((line) => `  ${line}`).join('\n'));
  const { json } = buildIndex(vaultDir);
  console.log(`Index rebuilt — ${json.count} entr${json.count === 1 ? 'y' : 'ies'}.`);
  return 0;
}

function cmdIndex(vaultDir, { flags }) {
  ensureVault(vaultDir);
  const { json, markdown } = buildIndex(vaultDir, { write: !flags.check });
  if (flags.check) {
    const indexFile = path.join(vaultDir, 'INDEX.md');
    const current = fs.existsSync(indexFile) ? fs.readFileSync(indexFile, 'utf8') : '';
    const stale = current.replace(/^- \*\*Generated:.*$/m, '') !== markdown.replace(/^- \*\*Generated:.*$/m, '');
    if (stale) {
      console.error('INDEX.md is out of date — run `npm run index`.');
      return 1;
    }
    console.log('INDEX.md is up to date.');
    return 0;
  }
  if (flags.json) {
    process.stdout.write(`${JSON.stringify(json, null, 2)}\n`);
    return 0;
  }
  const linked = json.entries.filter((e) => e.notebook).length;
  const where = path.relative(process.cwd(), vaultDir) || vaultDir;
  console.log(`Wrote ${where}/INDEX.md and ${where}/index.json — ${json.count} entries, ${linked} tied to a notebook page.`);
  return 0;
}

function matches(entry, { flags, positional }) {
  const text = positional.join(' ').toLowerCase();
  if (text) {
    const haystack = [entry.title, entry.body, entry.machine, entry.tags.join(' '), entry.notebook?.raw]
      .filter(Boolean)
      .join('\n')
      .toLowerCase();
    if (!haystack.includes(text)) return false;
  }
  if (flags.tag) {
    const want = String(flags.tag).replace(/^#/, '').toLowerCase();
    if (!entry.tags.includes(want)) return false;
  }
  if (flags.machine && !String(entry.machine || '').toLowerCase().includes(String(flags.machine).toLowerCase())) return false;
  if (flags.status && String(entry.status || '').toLowerCase() !== String(flags.status).toLowerCase()) return false;
  if (flags.notebook) {
    const ref = parseNotebookRef(flags.notebook);
    if (!entry.notebook) return false;
    if (ref && entry.notebook.id.toLowerCase() !== ref.id.toLowerCase()) return false;
    if (ref && ref.start != null) {
      if (entry.notebook.start == null) return false;
      if (entry.notebook.end < ref.start || entry.notebook.start > ref.end) return false;
    }
  }
  if (flags.page) {
    const page = Number(flags.page);
    if (!entry.notebook || entry.notebook.start == null) return false;
    if (page < entry.notebook.start || page > entry.notebook.end) return false;
  }
  if (flags.since && String(entry.date || '') < String(flags.since)) return false;
  if (flags.until && String(entry.date || '') > String(flags.until)) return false;
  return true;
}

function cmdSearch(vaultDir, args) {
  const { flags } = args;
  let results = loadEntries(vaultDir).filter((entry) => matches(entry, args));
  if (flags.limit) results = results.slice(0, Number(flags.limit));

  if (flags.json) {
    process.stdout.write(`${JSON.stringify(results, null, 2)}\n`);
    return 0;
  }
  if (!results.length) {
    console.log('No matching entries.');
    return 1;
  }
  for (const entry of results) {
    const stamp = `${entry.date || '????-??-??'}${entry.time ? ` ${entry.time}` : ''}`;
    const ref = entry.notebook ? formatNotebookRef(entry.notebook) : '—';
    console.log(`${stamp}  ${ref.padEnd(16)}  ${entry.title}`);
    const facets = [entry.machine, entry.status, entry.tags.map((t) => `#${t}`).join(' ')].filter(Boolean).join(' · ');
    console.log(`    ${entry.file}:${entry.line}${facets ? `  ${facets}` : ''}`);
    if (flags.full && entry.body) {
      console.log(entry.body.split('\n').map((line) => `    | ${line}`).join('\n'));
    }
  }
  console.log(`\n${results.length} entr${results.length === 1 ? 'y' : 'ies'}.`);
  return 0;
}

function cmdNotebooks(vaultDir, { flags }) {
  const entries = loadEntries(vaultDir).filter((e) => e.notebook);
  const byId = new Map();
  for (const entry of entries) {
    if (!byId.has(entry.notebook.id)) byId.set(entry.notebook.id, []);
    byId.get(entry.notebook.id).push(entry);
  }
  const report = [...byId.entries()]
    .sort((a, b) => a[0].localeCompare(b[0], undefined, { numeric: true }))
    .map(([id, rows]) => {
      const { covered, gaps, pages } = pageCoverage(rows);
      const dates = rows.map((r) => r.date).filter(Boolean).sort();
      return {
        notebook: id,
        entries: rows.length,
        pagesLogged: pages.length,
        covered: formatRanges(covered),
        gaps: formatRanges(gaps),
        firstLogged: dates[0] || null,
        lastLogged: dates[dates.length - 1] || null,
      };
    });

  if (flags.json) {
    process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
    return 0;
  }
  if (!report.length) {
    console.log('No notebook references logged yet.');
    return 1;
  }
  for (const row of report) {
    console.log(row.notebook);
    console.log(`  entries: ${row.entries}   pages logged: ${row.pagesLogged} (${row.covered})`);
    console.log(`  gaps:    ${row.gaps}`);
    console.log(`  span:    ${row.firstLogged || '—'} → ${row.lastLogged || '—'}`);
  }
  return 0;
}

async function main() {
  const [, , rawCommand, ...rest] = process.argv;
  const args = parseArgs(rest);
  const command = (rawCommand || 'help').replace(/^--/, '');
  if (command === 'help' || args.flags.help) {
    process.stdout.write(HELP);
    return 0;
  }
  const vaultDir = resolveVault(args.flags.vault);
  switch (command) {
    case 'log':
      return cmdLog(vaultDir, args);
    case 'index':
      return cmdIndex(vaultDir, args);
    case 'search':
      return cmdSearch(vaultDir, args);
    case 'notebooks':
      return cmdNotebooks(vaultDir, args);
    case 'sections':
      for (const section of SECTIONS) console.log(`${section.key}  ${section.dir.padEnd(16)} ${section.label}`);
      return 0;
    default:
      console.error(`Unknown command: ${command}\n`);
      process.stdout.write(HELP);
      return 1;
  }
}

main()
  .then((code) => process.exit(code ?? 0))
  .catch((error) => {
    console.error(`Error: ${error.message}`);
    process.exit(1);
  });
