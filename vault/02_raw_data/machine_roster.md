---
type: raw_data
---

# Machine roster

Fill this in from the shop floor. The ids here are what goes on the
`- **Machine:**` line of an entry, so keep them short and stable.

| Machine id | Make / model | Location | Notes |
| --- | --- | --- | --- |
| BROTHER-01 | Brother — _(model)_ | _(cell / bay)_ | _(fill in)_ |
| MAZAK-02 | Mazak — _(model)_ | _(cell / bay)_ | _(fill in)_ |

## Why ids instead of names
- **Tags:** #reference #convention

Machines get called different things by different people. One id per machine
means `notebook.js search --machine BROTHER-01` returns every page you ever
wrote about it, no matter what you called it that day.
