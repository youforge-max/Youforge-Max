# Youforge-Max — Video Editor Visual Design

Design-first spec. Dark, cyan-accented (YouForge brand), YouCut/CapCut-class layout.
No implementation here — this is the visual contract the Compose UI must match.

---

## 1. Design tokens

### Colour
| Token | Hex | Use |
|-------|-----|-----|
| `stage` | `#000000` | Preview background (true black, cinematic) |
| `bg` | `#0E0E10` | App / top-bar background |
| `surface` | `#1A1A1E` | Timeline strip, tool rail |
| `surfaceHi` | `#26262C` | Tool panel, cards, raised chips |
| `stroke` | `#2E2E36` | 1px dividers / card borders |
| `accent` | `#00E5FF` | Brand cyan — selection, scrubber, active icon, primary button |
| `accentDim` | `#0091A3` | Pressed / disabled accent |
| `text` | `#FFFFFF` | Primary text |
| `textMute` | `#9A9AA6` | Labels, durations, secondary |
| `textDim` | `#5A5A66` | Disabled |
| `danger` | `#FF4D5E` | Delete |

Accent used **sparingly** — selection, scrub fill, the single primary action. Everything
else is greyscale so the video is the brightest thing on screen.

### Typography (system / Roboto)
| Role | Size | Weight |
|------|------|--------|
| Top-bar title (none — icons only) | — | — |
| Status / timecode | 12sp | Medium |
| Tool label | 11sp | Medium |
| Clip duration chip | 10sp | SemiBold |
| Panel header | 14sp | Bold |
| Body / slider labels | 13sp | Regular |
| Empty-state heading | 18sp | Bold |

### Shape & spacing
- Corner radius: cards/thumbs `8dp`, buttons/chips `10dp`, panels `14dp` (top corners only).
- Base spacing unit `4dp`; gutters `12dp`; screen edge padding `12dp`.
- Touch targets ≥ `44dp`.

---

## 2. Screen layout (portrait, full-bleed dark)

```
╔════════════════════════════════════╗  bg #0E0E10
║  ↶   ↷            0:08    [ Export ]║  ← TOP BAR  48dp
╠════════════════════════════════════╣
║                                    ║
║                                    ║
║            ▶  PREVIEW              ║  ← STAGE  flex (fills, ~55–65%)
║             (video)                ║     bg #000
║                                    ║
║   ▏0:03 ────────●──────── 0:08     ║  ← SCRUB row  36dp, overlaid on stage bottom
╠════════════════════════════════════╣
║  ▕▮▮▮▮▮▕▮▮▕▮▮▮▮▮▮▮▏      ＋         ║  ← TIMELINE  filmstrip 72dp
║          ▲ playhead (fixed centre) ║     surface #1A1A1E
╠════════════════════════════════════╣
║  ✂   ⏩   🔊   🎞   🔤   🎵   🖼  ⋯ ║  ← TOOL RAIL  88dp
║ Trim Speed Vol Filter Text Music…  ║     surface #1A1A1E, scrolls →
╚════════════════════════════════════╝
```

Vertical budget (portrait phone ~640dp tall content):
- Top bar **48dp** fixed
- Stage **flex** (`weight 1`) — the dominant zone
- Timeline **72dp** fixed
- Tool rail **88dp** fixed
→ stage ends up ≈55–65% of height; on tablets it grows. Matches the "big preview" ask.

---

## 3. Components

### 3.1 Top bar (48dp, `bg`)
- Left: `↶` `↷` undo/redo — icon buttons, `text` when enabled, `textDim` disabled.
- Centre: live **timecode** `position / total` (e.g. `0:03 / 0:08`), `textMute`, 12sp.
- Right: **Export** — filled pill, `accent` bg, black label, 14sp SemiBold.
- Overflow `⋯` (between timecode and Export): Save / Load project.
- While exporting: Export pill → `Cancel` (outlined `danger`); a 2dp `accent` determinate
  progress line sits flush under the bar.

### 3.2 Preview stage (flex, `stage` #000)
- Video centred, letterboxed to its aspect; cyan never bleeds here.
- **Scrub row** overlaid along the bottom 36dp on a `#00000080` gradient:
  - left timecode (current), thin track, **cyan fill + cyan knob**, right timecode (total).
- Centre **play/pause** affordance: 64dp circular `#000000A0` w/ cyan glyph, fades out
  ~1.5s after play, reappears on tap. (Tap anywhere on stage toggles controls.)
- Burned-in overlays (title, stickers) render on top, draggable; selected overlay shows a
  dashed cyan bounding box w/ small handles.
- Empty state (no clips): centred — `🎬` 48sp, "No clips yet" 18sp Bold `text`,
  "Add a video to start" 13sp `textMute`, then a cyan **Add video** pill.

### 3.3 Timeline (72dp, `surface`) — **filmstrip**
- Horizontal scroll. Each clip = a strip whose **width ∝ duration** (e.g. `6dp/sec`,
  clamped 56–320dp), filled with repeated thumbnail frames (`~48dp` slots) so long clips
  literally show more of themselves.
- Clip strips separated by 2px `stroke`; rounded 8dp ends on first/last.
- **Selected** clip: 2dp `accent` border + slightly raised; trim handles (`▎ ... ▕`) appear
  at its two edges in cyan for drag-trim directly on the strip.
- **Playhead**: a fixed 2dp cyan vertical line at the strip's horizontal centre; the
  filmstrip scrolls *under* it (CapCut model) so "now" is always centre.
- Trailing **＋** tile (56dp, `surfaceHi`, cyan glyph) appends clips.
- Per-clip duration `0:05` chip bottom-left, 10sp, on `#00000099`.

### 3.4 Tool rail (88dp, `surface`)
- Horizontally scrolling row of tool items, each: 24sp emoji/glyph over 11sp label,
  68dp wide, ripple on tap.
- Default icon tint `textMute`; the **open** tool tints `accent` and gets a 2dp cyan
  underline.
- Order: ➕Add · ✂️Trim · ⏩Speed · 🔊Volume · 🔄Rotate · 🎞️Filter · ✨Transition ·
  🔤Text · 😀Sticker · 🎵Music · 🖼️Ratio · 🎚️Quality.

### 3.5 Tool panel (slides up over rail, `surfaceHi`, 14dp top radius)
- Opening a tool slides a panel up from the rail (≤40% screen height), dimming the rail.
- Header: `✂️ Trim` 14sp Bold left, **Done** text button (cyan) right, hairline divider.
- Body: that tool's controls (sliders / chips), scrollable.
- Chips: pill, `surfaceHi` bg, `stroke` border; **selected** = `accent` bg + black text.
- Sliders: cyan active track + knob, `stroke` inactive track.
- Clip-scoped tools with no selection show: "Select a clip in the timeline first."
- Tapping the dimmed area or Done closes back to the rail.

---

## 4. States & motion
- **Selection**: only ever cyan border + cyan handles; never fill the thumbnail.
- **Exporting**: rail + timeline dim to 40% and ignore touch; top progress line animates;
  Cancel available.
- **Transitions**: tool panel slide-up 180ms ease-out; controls auto-show/hide on stage
  fade 150ms; selecting a clip animates the filmstrip scroll to centre it 250ms.
- **Haptics**: light tick on clip select, trim-handle grab, and chip select.

---

## 5. Out of scope for v1 (note, not build)
Multi-track, keyframes, audio waveform under filmstrip, per-frame thumbnail cache. The
filmstrip frame strip should start with cheap evenly-sampled frames; a real cache lands later.
