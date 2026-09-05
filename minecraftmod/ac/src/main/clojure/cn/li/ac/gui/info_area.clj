(ns cn.li.ac.gui.info-area
  "AC themed InfoArea projection: pure state, no node-builder layer.
   Layout for the InfoArea panel lives in the declarative .ui.edn sources
   that bind against this namespace's snapshot; there is nothing here to
   compose imperatively anymore.

   Histogram geometry mirrors main `info-area-reactive/add-histogram!`:
   histogram.png 210×210 @ scale 0.4 → 84×84 frame at (8,-20), vertical
   bars at (56+idx·40, 78) × 16×120 in unscaled frame space.")

(def ^:private HIST-SCALE 0.4)
(def ^:private HIST-BAR-FULL-H (* 120.0 HIST-SCALE))
(def ^:private HIST-BAR-W (* 16.0 HIST-SCALE))
(def ^:private HIST-BAR-BASE-X (* 56.0 HIST-SCALE))
(def ^:private HIST-BAR-BASE-Y (* 78.0 HIST-SCALE))
(def ^:private HIST-BAR-GAP (* 40.0 HIST-SCALE))

(def ^:private ENERGY-COLOR (unchecked-int 0xFF25C4FF))
(def ^:private CAPACITY-COLOR (unchecked-int 0xFFFF6C00))
(def ^:private LIQUID-COLOR (unchecked-int 0xFF4CAF50))

(defn- clamp-ratio [ratio]
  ;; Upstream TechUI clamps the visible fill to [0.03, 1].
  (max 0.03 (min 1.0 (double ratio))))

(defn fill-ratio
  "Bar fill in [0,1]. A non-positive `maximum` yields 0 — never coerce 0→1
   (that pins the bar at 100% while the legend still shows a live absolute)."
  [value maximum]
  (let [value (double (or value 0.0))
        maximum (double (or maximum 0.0))]
    (if (pos? maximum)
      (max 0.0 (min 1.0 (/ value maximum)))
      0.0)))

(defn energy-hist
  "Raw hist entry for Energy (all TechUI pages)."
  [value maximum]
  (let [value (double (or value 0.0))
        maximum (double (or maximum 0.0))]
    {:id :energy :label "Energy"
     :ratio (fill-ratio value maximum)
     :value (format "%.0f IF" value)
     :color ENERGY-COLOR}))

(defn capacity-hist
  "Raw hist entry for Capacity / network load (all TechUI pages)."
  [value maximum]
  (let [value (double (or value 0.0))
        maximum (double (or maximum 0.0))]
    {:id :capacity :label "Capacity"
     :ratio (fill-ratio value maximum)
     :value (str (long value) "/" (long maximum))
     :color CAPACITY-COLOR}))

(defn liquid-hist
  "Raw hist entry for tank liquid (fusor / liquid machines)."
  [value maximum]
  (let [value (double (or value 0.0))
        maximum (double (or maximum 0.0))]
    {:id :liquid :label "Liquid"
     :ratio (fill-ratio value maximum)
     :value (format "%.0f mB" value)
     :color LIQUID-COLOR}))

;; Container atom keys that drive hist-bars. live-sync must sample these on
;; every TechUI page — do not re-list per gui_reactive.
(def hist-live-keys
  [:energy :max-energy :capacity :max-capacity :liquid-amount :tank-size])

(defn- hist-entry
  [idx {:keys [id label ratio value color]}]
  (let [ratio (max 0.0 (min 1.0 (double ratio)))
        color (unchecked-int (or color 0xFFFFFFFF))
        pct (clamp-ratio ratio)
        h (* HIST-BAR-FULL-H pct)
        x (+ HIST-BAR-BASE-X (* (double idx) HIST-BAR-GAP))
        y (+ HIST-BAR-BASE-Y (- HIST-BAR-FULL-H h))]
    {:id id
     :label (str label)
     :ratio ratio
     :value (str value)
     :color color
     :bar-x x :bar-y y :bar-w HIST-BAR-W :bar-h h
     ;; Rect/composite draw-list form for the histogram.png frame overlay.
     :kind :quad :x x :y y :w HIST-BAR-W :h h :rgba color}))

(defn project-histograms
  "Attach bar geometry + legend color (main add-histogram! layout) to raw entries.
   Each raw map needs :id :label :ratio :value and optional :color."
  [raw-entries]
  (mapv hist-entry (range) (or raw-entries [])))

(defn hist-bars
  "Draw-list for the histogram.png frame overlay (bound as rect items)."
  [histograms]
  (mapv #(select-keys % [:kind :x :y :w :h :rgba]) (or histograms [])))

;; ---------------------------------------------------------------------------
;; Draft-key contract (shared by every TechUI page — do not reimplement)
;;
;; Canonical view-state keys written by runtime focus rewrite:
;;   :node-name, :network-password
;; Form / legacy aliases that mean the same draft:
;;   :ssid, :network-ssid  ↔  :node-name
;;   :password             ↔  :network-password
;; ---------------------------------------------------------------------------

(def draft-alias-groups
  "Canonical draft-key → all keys that carry the same live text draft."
  {:node-name #{:node-name :ssid :network-ssid}
   :network-password #{:network-password :password}})

(defn draft-value
  "First non-empty string among `canonical` and its aliases in `m`.
   Empty string does not win — avoids (or \"\" good) INIT bugs."
  [m canonical]
  (when (map? m)
    (let [keys (get draft-alias-groups canonical #{canonical})]
      (some (fn [k]
              (when (contains? m k)
                (not-empty (str (get m k)))))
            (cons canonical (disj keys canonical))))))

(defn draft-present?
  "True when any alias key for `canonical` is present on `m` (even if \"\")."
  [m canonical]
  (when (map? m)
    (boolean (some #(contains? m %) (get draft-alias-groups canonical #{canonical})))))

(defn expand-drafts
  "Expand canonical drafts onto every alias key so form (:ssid/:password) and
   view (:node-name/:network-password) stay aligned after merge-drafts."
  [{:keys [node-name network-password] :as drafts}]
  (cond-> {}
    (contains? drafts :node-name)
    (assoc :node-name (str node-name)
           :network-ssid (str node-name)
           :ssid (str node-name))
    (contains? drafts :network-password)
    (assoc :network-password (str network-password)
           :password (str network-password))))

(defn project-form-drafts
  "Map presentation-form-state (any alias mix) onto canonical view draft keys
   for snapshot rebuilds. Used by every container — matrix :ssid and node
   :node-name both land on :node-name.

   Uses draft-value so an empty alias (e.g. :password \"\") cannot shadow a
   non-empty sibling — (or \"\" good) is truthy in Clojure and used to wipe
   INIT passwords."
  [form]
  (when (map? form)
    (cond-> {}
      (draft-present? form :node-name)
      (assoc :node-name (str (or (draft-value form :node-name) "")))
      (draft-present? form :network-password)
      (assoc :network-password (str (or (draft-value form :network-password) ""))))))

(defn sync-view-into-form
  "Pure merge: push live view drafts into a form map (all aliases)."
  [form view]
  (let [form (or form {})
        name-v (when (draft-present? view :node-name)
                 (str (or (draft-value view :node-name) "")))
        pass-v (when (draft-present? view :network-password)
                 (str (or (draft-value view :network-password) "")))]
    (merge form
           (when (some? name-v)
             (expand-drafts {:node-name name-v}))
           (when (some? pass-v)
             (expand-drafts {:network-password pass-v})))))

(defn payload-drafts
  "Normalize a text-change field/value into expanded draft keys."
  [field value]
  (let [value (str (or value ""))
        canonical (case field
                    (:node-name :ssid :network-ssid) :node-name
                    (:password :network-password) :network-password
                    nil)]
    (when canonical
      (expand-drafts {canonical value}))))

(defn apply-drafts-to-fields
  "Overlay top-level draft keys onto info-area field :value by :draft-key.

   Resolves aliases (:ssid → :node-name, :password → :network-password) so a
   page that stores form :ssid still refreshes the :node-name draft-key row.
   Shared by every TechUI page — do not reimplement per container."
  [state]
  (let [fields (get-in state [:info-area :fields])]
    (if-not (vector? fields)
      state
      (let [patched
            (mapv (fn [field]
                    (let [dk (:draft-key field)]
                      (if (and (keyword? dk) (draft-present? state dk))
                        (assoc field :value (str (or (draft-value state dk) "")))
                        field)))
                  fields)]
        (assoc-in state [:info-area :fields] patched)))))

(defn field-entry
  "Normalize one info-area field with draft routing metadata."
  [{:keys [id label value editable? masked? draft-key] :as m}]
  (let [id (or id (keyword (str "field-" (hash label))))
        editable? (boolean editable?)
        draft-key (or draft-key
                      (case id
                        :password :network-password
                        id))]
    (cond-> {:id id
             :label (str label)
             :value (str (or value ""))
             :editable? editable?
             :readonly? (not editable?)
             :draft-key draft-key}
      (some? masked?) (assoc :masked? (boolean masked?)))))

(defn snapshot
  [data policy]
  (let [initialized? (boolean (:initialized data))
        owner? (boolean (:owner? policy))
        energy (double (or (:energy data) 0.0))
        max-energy (double (or (:max-energy data) 0.0))
        capacity (double (or (:load data) (:capacity data) 0.0))
        max-capacity (double (or (:max-capacity data) 0.0))
        load-ratio (fill-ratio capacity max-capacity)
        energy-ratio (fill-ratio energy max-energy)
        raw-hists (cond-> []
                    (contains? data :energy)
                    (conj (energy-hist energy max-energy))
                    (or (contains? data :load) (contains? data :capacity))
                    (conj (capacity-hist capacity max-capacity)))
        histograms (project-histograms raw-hists)
        ;; Main node order after hist rows + "-- Info --": Range, Owner,
        ;; then optional Node Name / Password (editable when owner).
        fields (mapv field-entry
                     (cond-> [{:id :range :label "Range" :value (str (or (:range data) 0))}
                              {:id :owner :label "Owner" :value (str (or (:owner data) "Unknown"))}]
                       (or (contains? data :ssid) (contains? data :node-name))
                       (conj {:id :node-name :label "Node Name"
                              :value (str (or (:ssid data) (:node-name data) ""))
                              :editable? (and initialized? owner?)
                              :draft-key :node-name})
                       (contains? data :password)
                       (conj {:id :password :label "Password"
                              :value (str (or (:password data) ""))
                              :editable? (and initialized? owner?)
                              :masked? true
                              :draft-key :network-password})))
        sep-label "-- Info --"]
    {:title "Info"
     ;; Matches add-sepline! text formatting on main.
     :sep-label sep-label
     :sep-visible? true
     :initialized? initialized?
     :editable? (and initialized? owner?)
     :load-ratio load-ratio
     :histograms histograms
     :hist-bars (hist-bars histograms)
     :fields fields}))
