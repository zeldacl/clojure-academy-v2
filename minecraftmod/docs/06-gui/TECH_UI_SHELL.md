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
4. Container `merge-drafts` / form sync keep mapping `:password` → `:network-password`

Top-level draft keys (`:node-name`, `:network-password`, …) remain write
targets; they are not removed from the runtime contract.
