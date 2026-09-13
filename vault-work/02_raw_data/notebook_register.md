---
type: raw_data
---

# Physical notebook register — work

Work notebooks only. The personal Root Sandbox notebook lives in the other
vault and is never logged here.

| Notebook id | Book | Sheets | Started | Filled | Where it lives |
| --- | --- | --- | --- | --- | --- |
| WL-01 | Large notebook | 70 | _(date)_ | — | _(toolbox / desk)_ |
| WS-01 | Small notebook | 100 | _(date)_ | — | _(pocket / on the floor)_ |

`WL` = work large, `WS` = work small. When a book fills up the next one is
`WL-02` / `WS-02` — the ids stay sortable and the old index stays valid.

## Page numbering
- **Tags:** #reference #convention

Number **every writable side**, straight through, starting at 1 on the first
side you write on. A 70-sheet book therefore runs to about p.140 and a
100-sheet book to about p.200. Don't number sheets — "p.41" has to mean one
side of one piece of paper or the index sends you to the wrong half of a page.

Number in the same corner every time, in pen, before you write on the page.

## What a work entry needs
- **Tags:** #reference #convention

- **Notebook:** `WL-01 p.41-42` — the book and the side(s) the note is on.
- **Machine:** the Center number from `machine_roster.md` (`00255`), not a
  nickname. One id per machine is what makes
  `npm run search:work -- --machine 00255` return every page about it.
- **Status:** `open` while it is still hanging — open items get pulled to the
  top of `INDEX.md` so nothing dies on a page you stopped turning to.
- **Tags:** whatever you'd actually search later: `#alarm`, `#spindle`,
  `#coolant`, `#pm`, `#waiting-on-parts`, `#atc`, `#way-lube`.
