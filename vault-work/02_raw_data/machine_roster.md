---
type: raw_data
source: Machine Asset Tags_2023.xls (printout dated 2024-05-09)
---

# Machine roster — J&M Machined Products

The **Center number is the machine id.** It already exists on the asset tag, it
is already what the shop paperwork uses, and it is unique — so it is what goes
on the `- **Machine:** 00255` line of a notebook entry. Never log a nickname.

Serial numbers are deliberately not in this file: this repository is public.
Keep them in the source spreadsheet
(`C:\Users\Public\Documents\Machine Asset Tags_2023.xls`).

## Machining centers and mills

| Center | Year | Description | Control |
| --- | --- | --- | --- |
| 00106 | 2006 | Nexus 510C vertical mill | 640M Nexus |
| 00108 | 1998 | FJV-25 vertical mill | M Plus |
| 00112 | 2012 | Nexus 530C | Smart |
| 00120 | 2006 | Small robot | — |
| 00130 | — | Large robot | — |
| 00202 | 2013 | HCN-6000 | Matrix Nexus 2 |
| 00209 | 2003 | Hurco VM1 | — |
| 00211 | 2000 | Brother TC-S2A-0 | — |
| 00212 | 2000 | Brother TC-S2A-0 | — |
| 00213 | 2003 | Brother TC-S2B | — |
| 00214 | 2005 | PFH-5800 horizontal mill | 640M |
| 00215 | 2004 | Brother TC-32A | — |
| 00219 | 2005 | Brother TC-S2C-0 tap center | — |
| 00220 | 2006 | Brother TC-S2C-0 tap center | — |
| 00221 | 2008 | Brother TC-S2C-0 tap center | — |
| 00222 | 2010 | Brother TC-S2C-0 tap center | — |
| 00223 | 2006 | Brother TC-S2N0 | — |
| 00224 | 2010 | Brother TC-S2C-0 tap center | — |
| 00225 | 2010 | Brother TC-S2D tap center | — |
| 00226 | 2005 | Brother TC-32B, 2 pallet | — |
| 00227 | 2010 | Brother TC-S2D | — |
| 00228 | 2006 | Brother TC22B | — |
| 00229 | 2006 | Brother TC-32B-QT, 2 pallet | — |
| 00230 | 2012 | Mori Seiki NH 5000 DCG | — |
| 00231 | 2005 | Brother TC-S2B-O | — |
| 00235 | 2013 | Brother TC-32BQT N | — |
| 00236 | 2003 | Brother TC-32A | — |
| 00237 | 2004 | Brother TC-32A | — |
| 00238 | 2015 | Brother Speedio S1000X1 | C controller |
| 00239 | 2015 | Brother Speedio R450X1 | C controller |
| 00240 | 2014 | Brother Speedio R450X1 | C controller |
| 00241 | 2000 | Brother TC-S2A | — |
| 00255 | 2015 | Brother S700X1 | C controller |

## Turning, VTL and plant equipment

| Center | Year | Description | Control |
| --- | --- | --- | --- |
| 01101 | 2003 | Nexus 100 Quick Turn (PL#2) | — |
| 01102 | 2000 | QT 350 Fusion (PL#2) | 640T |
| 01103 | 2010 | QT-350 Mazak | 640T |
| 01104 | 2014 | QTS-350 Smart | Nexus ⚠ |
| 01105 | 2008 | Nexus 350 II bar lathe | Smart ⚠ |
| 01106 | 1997 | QT-30 | Matrix Nexus |
| 01112 | 1988 | QT-15 lathe | T Plus |
| 01114 | 1995 | QT18N lathe | CAM T2 |
| 01115 | 1995 | QT-15N | T Plus |
| 01116 | 1995 | QT-20N | T Plus |
| 01124 | 1992 | QT 15N lathe (T32) | T Plus |
| 01126 | 1998 | Dual QT20 | T32B |
| 01128 | 1997 | Q-Turn 20 lathe | T Plus |
| 01129 | 2006 | Super Quick Turn | T Plus |
| 01135 | 2012 | Q-Turn 200 QTS | Fusion 640T |
| 01145 | 2004 | VTL lathe #1 | Smart |
| 01146 | 2005 | VTL lathe #2 | Fanuc |
| 01147 | 2014 | Yama Seiki GA-2800 | Fanuc |
| 01148 | 2014 | Yama Seiki GA-2800 | Fanuc |
| 01149 | 2006 | Brother TC-S2A-0 | Fanuc ⚠ |
| 01150 | 2019 | Q-Turn 250 Mazak | Smooth C |
| 01151 | 2019 | Q-Turn 250 Mazak | Smooth C |
| 01152 | 2010 | HCN 5000-II | Matrix Nexus |
| 01153 | 2011 | HCN 5000-II | Matrix Nexus |
| 01154 | 2021 | Q-Turn 250 Mazak | Smooth G |
| 01155 | 2022 | Mazak VC-Ez20 | Smooth G |
| 01156 | 2022 | Q-Turn 250 Mazak | Smooth G |
| 01157 | 2022 | Q-Turn 250MY Mazak | Smooth G |
| 01159 | 2008 | Q-Turn 200N | Nexus |
| 01160 | 2021 | Doosan V400R | Fanuc |
| 01200 | 2019 | Atlas Copco air compressor | — |
| 01210 | 2019 | Atlas Copco air dryer | — |

## Transcription notes
- **Tags:** #reference #verify

The CONTROL column printed offset by about half a row on both pages, so it was
re-aligned by count: 29 control values map to the 29 rows from 01102 to 01160,
leaving 01101 and the two Atlas Copco units blank. Most rows corroborate
themselves (01115/01116 are named "T-PLUS" and land on T Plus; the 2019 Q-Turn
250s land on Smooth C; the Yama Seikis and the Doosan on Fanuc), but three rows
marked ⚠ read oddly and are worth a look at the source spreadsheet:

- **01104 / 01105** look swapped — a QTS-350 *Smart* on "Nexus" and a *Nexus*
  350 II on "Smart".
- **01149** is a Brother tap center listed with a Fanuc control.

Source spelling kept where it is unambiguous; corrected in this file:
"MORI SEIKE" → Mori Seiki, "SPEDIO" → Speedio. `00223 TC-S2N0` is transcribed
as printed and may be `TC-S2N-0`.

Blank year and serial on 00130 (large robot) are blank in the source too.
