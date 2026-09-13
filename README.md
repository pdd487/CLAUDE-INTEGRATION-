# Root Sandbox

A markdown vault for unfiltered daily logging, plus the tooling that ties a
**physical notebook** to it: every entry can name the notebook and page it came
from, and `INDEX.md` turns that into a lookup that works in both directions —
page → entry, and machine/tag/date → page.

The paper notebook stays the primary capture device. It works with gloves on,
at a machine, with no battery. This repo is the index for it.

## Quick start

No dependencies — Node 18+ is all you need.

```bash
# log a page you just wrote, into today's core dump
npm run log -- "Spindle alarm 1013" --notebook NB-03 --pages 41-42 \
  --machine BROTHER-01 --status open --tags alarm,spindle \
  --body "Reseated the encoder cable, ran it 20 min, no repeat."

# rebuild vault/INDEX.md and vault/index.json
npm run index

# find it again
npm run search -- coolant --machine MAZAK-02
npm run search -- --notebook NB-03 --page 41     # what's on that page?
npm run search -- --status open                  # what's still open?

# which pages of which notebooks are logged, and what's missing
npm run notebooks
```

`npm run log` rebuilds the index for you, so the two stay in step.
Full flag list: `node scripts/notebook.js help`.

## The entry format

An entry is a `## ` heading followed by optional `- **Label:** value` lines.
That is the whole contract — everything after those lines is free text, and
the file is plain markdown that reads fine in any editor.

```markdown
## 14:32 — Spindle alarm 1013
- **Notebook:** NB-03 p.41-42
- **Machine:** BROTHER-01
- **Status:** open
- **Tags:** #alarm #spindle

Reseated the encoder cable, ran it 20 min, no repeat.
Sketch of the connector is on p.42.
```

| Label | Meaning |
| --- | --- |
| `Notebook:` | Physical source: `NB-03 p.41`, `NB-03 p.41-42`, or just `NB-03`. |
| `Machine:` | Machine/asset id from `vault/02_raw_data/machine_roster.md`. |
| `Status:` | Free text; `open` pulls the entry to the top of `INDEX.md`. |
| `Tags:` | With or without `#`. Tags written inline in the body count too. |
| `Date:` / `Time:` | Only needed to override the file's date or the heading time. |

Anything else you add (`- **Work order:** 44821`) is preserved in the file and
carried into `index.json`, it just gets no dedicated index section.

## What the index gives you

`npm run index` writes two files:

- **`vault/INDEX.md`** — open items, then a section per physical notebook
  (a page-ordered table plus **which page ranges are logged and which are
  gaps**), then by machine, by tag, and chronological. Every row links to the
  exact heading in the source file.
- **`vault/index.json`** — the same data as structured records, for the UI in
  the project plan or any other tooling.

`npm run index:check` exits non-zero when `INDEX.md` is stale, which is what
you want in a pre-commit hook or CI.

## Conventions that make the index work

1. **Number the pages.** Corner of every page, starting at 1. The index is
   only as good as the numbers on the paper.
2. **One id per notebook**, written on the cover: `NB-01`, `NB-02`, ...
   Register them in `vault/02_raw_data/notebook_register.md`.
3. **One id per machine**, not a nickname: `BROTHER-01`, `MAZAK-02`.
   Register them in `vault/02_raw_data/machine_roster.md`.
4. **Log the page, don't re-type the page.** A title, the page reference and a
   line of context is enough to find the paper again. Transcribe in full only
   when it is worth having searchable.
5. Re-logging pages that are already claimed prints a warning with the entry
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
└── vault/
    ├── INDEX.md              # generated
    ├── index.json            # generated
    ├── 01_core_dumps/        # daily append-only logs (YYYY-MM-DD_dump.md)
    ├── 02_raw_data/          # notebook register, machine roster, references
    ├── 03_sandbox/           # messy, unverified working notes
    └── 04_synthesis/         # refined procedures built from the above
```

Entries default to `01_core_dumps`; `--section 02|03|04` (or
`raw_data|sandbox|synthesis`) puts one elsewhere, and `--file name.md` targets
a specific file. Point the tooling at a different vault with `--vault <dir>` or
`SANDBOX_VAULT`.

## Tests

```bash
npm test
```

## Next

Phase 1 of the project plan (React/Tailwind UI: sidebar, editor, quick-dump
button) reads `vault/index.json` — the CLI above is the data layer it sits on.
