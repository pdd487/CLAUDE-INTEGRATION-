# Base Bench

A hex / decimal / octal / binary converter and calculator with a clickable bit
map and a 0–255 reference chart. Static files, no build step, no dependencies —
the fonts are vendored in `fonts/` so the app never reaches the network.

## Publishing it

Served from GitHub Pages at:

    https://pdd487.github.io/CLAUDE-INTEGRATION-/apps/base-calculator/

To turn Pages on: repo **Settings → Pages → Source: Deploy from a branch**,
pick branch `claude/hex-octo-binary-calculator-wlvv6s` and folder `/ (root)`,
then Save. First build takes a couple of minutes.

## Installing it on a phone

**iPhone** — open the URL in *Safari* (this does not work in Chrome on iOS),
tap Share, then **Add to Home Screen**.

**Android** — open the URL in Chrome, tap **⋮**, then **Install app** (or
**Add to Home screen**).

Open it once while on wifi. After that it runs with no signal at all.

## One-file version

`base-bench-standalone.html` is the entire app in a single file, fonts and all
— no server, no network, nothing beside it. Save it anywhere and open it in a
browser. Useful when Pages isn't set up, or for dropping on a machine that has
no internet at all. It can't be pinned to a phone home screen, though; that
needs a real URL.

Rebuild it after any change to `index.html`:

    node build-standalone.cjs

## Files

| File | Purpose |
| --- | --- |
| `index.html` | The whole app — markup, styles and logic inline |
| `sw.js` | Service worker; precaches everything for offline use |
| `manifest.webmanifest` | App name, icons, standalone display |
| `fonts/` | Archivo + IBM Plex Mono, latin subset, self-hosted |
| `icon-*.png` | Home screen icons (180 for iOS, 192/512 for Android) |
| `base-bench-standalone.html` | Generated one-file build with the fonts inlined |
| `build-standalone.cjs` | Regenerates that one-file build from `index.html` |

## Notes

- All math is `BigInt`, so large values stay exact. Integers only: division
  reports a quotient and a remainder rather than a decimal.
- The word size selector (8/16/32/64) governs the bit map and masks the
  bitwise operations. Plain arithmetic is never masked.
- Negatives display with a leading minus in every base; the bit map shows the
  two's complement for the selected word size and says so when it does.
- **Shipping a change:** bump `CACHE` in `sw.js` (e.g. `base-bench-v2`),
  otherwise phones that already installed it keep serving the old cache.
- `index.html` is a complete HTML document. If you ever republish it as a
  Claude Artifact, strip the `<!doctype>`/`<head>`/`<body>` wrapper first —
  Artifacts supply their own.
