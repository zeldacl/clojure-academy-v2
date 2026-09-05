# TechUI Shell + Slots

Compile-time composition for Academy TechUI container views (290×187).
Pages fill named slots; shared chrome / tabs / wireless / histogram live in
fragments under `ac/src/presentation/resources/academy/shared/`.

See also [PRESENTATION_V3.md](PRESENTATION_V3.md).

## Shell

- Fragment: `academy/shared/tech_ui_shell.edn`
- Design size: **290×187**, host `:scale-policy :fit`
- Required slots (fragment `:slots/required`):
  - `:inv` — left page (inventory / machine overlay / forms)
  - `:info-body` — content inside the info clip below the histogram
- Always composed by the shell:
  - `tech_tabs` (left strip)
  - `wireless_page` (hidden unless `:wireless-page-visible?`)
  - `info_area_chrome` + clip `(179,5)×100×177` + `info_area_histogram` + `:info-body`

### Default visibility (fragment state-schema)

| Key | Default intent |
|-----|----------------|
| `:tech-tabs` | `[]` (nothing drawn) |
| `:inv-page-visible?` | `true` |
| `:wireless-page-visible?` | `false` |

Tabbed hosts (`presentation-tech-tabs?`) override via `tech-tabs/snapshot-keys`.

### Site form

```edn
{:type :include :src "academy/shared/tech_ui_shell"
 :slots
 {:inv {…}
  :info-body
  {:type :include :src "academy/shared/info_fields"
   ;; or a column wrapping info_fields + progress / network tails
   }}}
```

`:slots` is consumed by the compiler and must not remain on the fragment root.
Unknown slot names and unfilled required slots fail compilation. A leftover
`:type :slot` after expansion also fails.

## `info-area.fields`

Shared UI: `academy/shared/info_fields.edn` (optional `:sep-label` + repeater).

Each field map:

```clojure
{:id :node-name            ; text-change / submit routing (:node-name / :password / …)
 :draft-key :node-name     ; top-level view-state write key
                           ; :password uses :network-password
 :label "Node Name"
 :value "..."
 :editable? true}          ; false → read-only row; true → bracket editor row
```

- `:sep-label` on `info-area` is optional; blank/missing hides the separator.
- Progress bars stay as **sibling nodes** in `:info-body` (not field kinds).

### Editable rows (runtime)

Template binds text-input to `[:item :value]` (no static `:semantics :field`).

On focus of a FOCUSABLE under a collection item:

1. `:field` ← `(:id item)` (else static semantics / bind-path peek)
2. If the text bind starts with `:item` and the item has `:draft-key`:
   - focus `:path` ← `[:state (:draft-key item)]`
   - store `:item-index` from the hit
3. `edit-input-state` writes the draft-key path **and**
   `[:info-area :fields idx :value]` so paint stays live
4. Container `merge-drafts` keeps draft keys and calls
   `info-area/apply-drafts-to-fields` so a page snapshot that rebuilds
   fields from tile/network data cannot undo backspace/typing
5. **Draft aliases (unified, all pages):** form/view may use either name —
   `:ssid` / `:network-ssid` ↔ `:node-name`, `:password` ↔ `:network-password`.
   Projection lives in `info-area` (`project-form-drafts`, `expand-drafts`,
   `draft-value`); do not reimplement per container.
6. **Do not reuse `:network-password` for the wireless-tab connect box.**
   That key is reserved for info-area password drafts. Wireless connect text
   binds `:wireless-connect-password` only (see `snapshot-for`).

Top-level draft keys (`:node-name`, `:network-password`, …) remain write
targets; they are not removed from the runtime contract.

## Histogram live update (unified)

All TechUI pages share one hist contract — do not reimplement per container:

1. **Raw entries** — `info-area/energy-hist`, `capacity-hist`, `liquid-hist`
   (+ `fill-ratio`). Never coerce a zero max to `1.0` (pins the bar at 100%).
2. **Single projection** — `info-area/shared-info-hist` only:
   - `hist-from-container` when the container has `:energy` (phase/wind/solar
     gens, imag-fusor, metal-former, ability-interferer, wireless-node, …)
   - else `hist-from-network` for wireless-matrix (`:presentation-network`)
   - `presentation_container` **always** merges this onto `:info-area` after
     page snapshots (empty vectors when neither source applies)
   - **Do not** build hist in `generic-info-area`, `info-area/snapshot`, or
     page `:presentation-snapshot-fn` (fields/INIT chrome only)
   - wireless-node supplies `:presentation-energy-max-fn` (tier max) so a
     stale `:max-energy` atom cannot pin the bar at 100%
3. **Geometry** — hist-bars carry `:x/:y/:w/:h/:rgba`; shell fragment
   `info_area_histogram.edn` paints them as `:rect` with bound size
   (`direction :none` so x/y are absolute in the histogram frame).
   Do **not** use `:composite` here — composite 0×0 nodes do not remeasure
   bar height on live energy updates.
   Backend `draw-rect-run!` must use **float** fills (`fillColoredQuad`);
   integer `GuiGraphics.fill` truncates to whole pixels (~48 steps) so bars
   look stuck until energy crosses each pixel threshold.
   Visible fill ratio is `[0,1]` with **no** 0.03 floor.
4. **Live rebuild** — `live-sync-fingerprint` always samples
   `info-area/hist-live-keys` and `:presentation-network` (matrix).
5. **present!** — when view-state changes, layout/paint stamps clear so
   hist rects remeasure.

### Consumers (TechUI shell histogram)

| Surface | Hist source | Notes |
| --- | --- | --- |
| phase-gen / wind / solar / fusor / metal-former / interferer | `hist-from-container` | Synced `:energy`+`:max-energy` (scaled doubles) |
| wireless-node | `hist-from-container` + `:presentation-energy-max-fn` | Was broken: custom snapshot overrode hist |
| wireless-matrix | `hist-from-network` | Capacity only; no `:energy` |
| energy-converter | none (no TechUI hist) | Wireless-only page |
| developer | none (uses `:energy-ratio` progress) | Not info-area histogram |
