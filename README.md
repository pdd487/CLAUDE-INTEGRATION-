# Root Sandbox

A markdown vault for unfiltered daily logging, plus the tooling that ties a
**physical notebook** to it: every entry names the notebook and page it came
from, and `INDEX.md` turns that into a lookup that works both directions —
page → entry, and machine/tag/date → page.

The paper notebook stays the primary capture device. It works with gloves on,
at a machine, with no battery. This repo is the index for it.

## Two vaults

| Vault | For | Notebooks |
| --- | --- | --- |
| `vault/` | **Root Sandbox — personal. Not for work.** | `RS-01` (green Meijer spiral, 70 sheets) |
| `vault-work/` | Shop maintenance logging | `WL-01` (large, 70 sheets), `WS-01` (small, 100 sheets) |

Same tooling, same entry format, separate contents — the work vault is the only
one that carries a machine roster, and the personal vault never names a machine.
Work commands are the `:work` variants of every script below.

## Quick start

No dependencies — Node 18+ is all you need.

```bash
# log a work page you just wrote, into today's core dump
npm run log:work -- "Spindle alarm 1013" --notebook WL-01 --pages 41-42 \
  --machine 00255 --status open --tags alarm,spindle \
  --body "Reseated the encoder cable, ran it 20 min, no repeat."

# find it again
npm run search:work -- coolant --machine 01154
npm run search:work -- --notebook WL-01 --page 41     # what's on that page?
npm run search:work -- --status open                  # what's still open?

# which pages of which notebooks are logged, and what's missing
npm run notebooks:work

# rebuild vault-work/INDEX.md and vault-work/index.json
npm run index:work
```

Drop the `:work` for the personal vault: `npm run log`, `npm run search`,
`npm run index`, `npm run notebooks`.

Logging rebuilds that vault's index for you, so the two stay in step.
Full flag list: `node scripts/notebook.js help`.

## The entry format

An entry is a `## ` heading followed by optional `- **Label:** value` lines.
That is the whole contract — everything after those lines is free text, and the
file is plain markdown that reads fine in any editor.

```markdown
## 14:32 — Spindle alarm 1013
- **Notebook:** WL-01 p.41-42
- **Machine:** 00255
- **Status:** open
- **Tags:** #alarm #spindle

Reseated the encoder cable, ran it 20 min, no repeat.
Sketch of the connector is on p.42.
```

| Label | Meaning |
| --- | --- |
| `Notebook:` | Physical source: `WL-01 p.41`, `WL-01 p.41-42`, or just `WL-01`. |
| `Machine:` | Center number from `vault-work/02_raw_data/machine_roster.md`. |
| `Status:` | Free text; `open` pulls the entry to the top of `INDEX.md`. |
| `Tags:` | With or without `#`. Tags written inline in the body count too. |
| `Date:` / `Time:` | Only needed to override the file's date or the heading time. |

Anything else you add (`- **Work order:** 44821`) is preserved in the file and
carried into `index.json`, it just gets no dedicated index section.

## What the index gives you

`npm run index` / `npm run index:work` writes two files per vault:

- **`INDEX.md`** — open items, then a section per physical notebook (a
  page-ordered table plus **which page ranges are logged and which are gaps**),
  then by machine, by tag, and chronological. Every row links to the exact
  heading in the source file.
- **`index.json`** — the same data as structured records, for the UI in the
  project plan or any other tooling.

`npm run index:check` exits non-zero when `INDEX.md` is stale, which is what you
want in a pre-commit hook or CI.

## Conventions that make the index work

1. **Number every writable side**, straight through from 1 — not sheets. A
   70-sheet book runs to about p.140. Same corner every time, in pen, before you
   write on the page. The index is only as good as the numbers on the paper.
2. **One id per notebook**, written on the cover: `WL-01`, `WS-01`, `RS-01`.
   Next book is `-02`. Registered in each vault's `02_raw_data/notebook_register.md`.
3. **The Center number is the machine id** — `00255`, not "the S700" and not
   "the new Brother". It's already on the asset tag and already unique, so one
   search returns every page about that machine no matter what it got called
   that day. Roster: `vault-work/02_raw_data/machine_roster.md`.
4. **Log the page, don't re-type the page.** A title, the page reference and a
   line of context is enough to find the paper again. Transcribe in full only
   when it's worth having searchable.
5. Re-logging pages that are already claimed prints a warning naming the entry
   that has them, so the same page doesn't get filed twice.

## Layout

```text
root-sandbox/
├── scripts/
│   ├── notebook.js           # CLI: log / index / search / notebooks
│   ├── lib/
│   │   ├── entries.js        # parse vault markdown into entries
│   │   ├── log.js            # append an entry to the right file
│   │   ├── build-index.js    # render INDEX.md + index.json
│   │   └── paths.js          # vault + section resolution
│   └── test/                 # node --test suite
├── vault/                    # personal — Root Sandbox
└── vault-work/               # shop maintenance
    ├── INDEX.md              # generated
    ├── index.json            # generated
    ├── 01_core_dumps/        # daily append-only logs (YYYY-MM-DD_dump.md)
    ├── 02_raw_data/          # notebook register, machine roster, references
    ├── 03_sandbox/           # messy, unverified working notes
    └── 04_synthesis/         # refined procedures built from the above
```

Entries default to `01_core_dumps`; `--section 02|03|04` (or
`raw_data|sandbox|synthesis`) puts one elsewhere, and `--file name.md` targets a
specific file. Any other vault works too: `--vault <dir>` or `SANDBOX_VAULT`.

## A note on the machine roster

`vault-work/02_raw_data/machine_roster.md` is transcribed from the
`Machine Asset Tags_2023.xls` printout dated 2024-05-09. **Serial numbers are
deliberately omitted** — this repository is public. They stay in the source
spreadsheet. Three control-column rows are flagged `⚠` in that file and are
worth checking against the source.

## Tests

```bash
npm test
```

## Next

Phase 1 of the project plan (React/Tailwind UI: sidebar, editor, quick-dump
button) reads `index.json` — the CLI above is the data layer it sits on.
