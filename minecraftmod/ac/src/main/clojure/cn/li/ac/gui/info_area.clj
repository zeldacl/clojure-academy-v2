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

(defn- clamp-ratio [ratio]
  ;; Upstream TechUI clamps the visible fill to [0.03, 1].
  (max 0.03 (min 1.0 (double ratio))))

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
     ;; Composite draw-list form for the histogram.png frame overlay.
     :kind :quad :x x :y y :w HIST-BAR-W :h h :rgba color}))

(defn project-histograms
  "Attach bar geometry + legend color (main add-histogram! layout) to raw entries.
   Each raw map needs :id :label :ratio :value and optional :color."
  [raw-entries]
  (mapv hist-entry (range) (or raw-entries [])))

(defn hist-bars
  "Composite draw-list for the histogram.png frame overlay."
  [histograms]
  (mapv #(select-keys % [:kind :x :y :w :h :rgba]) (or histograms [])))

(defn snapshot
  [data policy]
  (let [initialized? (boolean (:initialized data))
        owner? (boolean (:owner? policy))
        energy (double (or (:energy data) 0.0))
        max-energy (max 1.0 (double (or (:max-energy data) 1.0)))
        capacity (double (or (:load data) (:capacity data) 0.0))
        max-capacity (max 1.0 (double (or (:max-capacity data) 1.0)))
        load-ratio (max 0.0 (min 1.0 (/ capacity max-capacity)))
        energy-ratio (max 0.0 (min 1.0 (/ energy max-energy)))
        raw-hists (cond-> []
                    (contains? data :energy)
                    (conj {:id :energy :label "Energy"
                           :ratio energy-ratio
                           :value (format "%.0f IF" energy)
                           :color ENERGY-COLOR})
                    (or (contains? data :load) (contains? data :capacity))
                    (conj {:id :capacity :label "Capacity"
                           :ratio load-ratio
                           :value (str (long capacity) "/" (long max-capacity))
                           :color CAPACITY-COLOR}))
        histograms (project-histograms raw-hists)
        ;; Main node order after hist rows + "-- Info --": Range, Owner,
        ;; then optional Node Name / Password (editable when owner).
        fields (cond-> [{:id :range :label "Range" :value (str (or (:range data) 0))}
                        {:id :owner :label "Owner" :value (str (or (:owner data) "Unknown"))}]
                 (or (contains? data :ssid) (contains? data :node-name))
                 (conj {:id :node-name :label "Node Name"
                        :value (str (or (:ssid data) (:node-name data) ""))
                        :editable? (and initialized? owner?)})
                 (contains? data :password)
                 (conj {:id :password :label "Password"
                        :value (str (or (:password data) ""))
                        :editable? (and initialized? owner?)
                        :masked? true}))]
    {:title "Info"
     ;; Matches add-sepline! text formatting on main.
     :sep-label "-- Info --"
     :initialized? initialized?
     :editable? (and initialized? owner?)
     :load-ratio load-ratio
     :histograms histograms
     :hist-bars (hist-bars histograms)
     :fields fields}))
