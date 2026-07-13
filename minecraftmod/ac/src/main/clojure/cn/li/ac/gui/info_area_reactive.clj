(ns cn.li.ac.gui.info-area-reactive
  "Reactive TechUI info-area — replaces tech-ui-common add-property/add-sepline/add-button."
  (:require [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.node :as node]
            [cn.li.mcmod.ui.slot-write :as slot-write]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.events :as events])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]
           [cn.li.mcmod.ui.node INode]))

(def ^:private row-h 10.0)
(def ^:private label-w 42.0)
(def ^:private idle-color 0xFFFFFFFF)
(def ^:private edit-color 0xFF2180D8)

(defn ensure-shell!
  "Ensure :info-area group exists on runtime (sibling to machine panel).
   Background uses :nine-slice matching upstream BlendQuad (blend_quad.png 3×3 + line.png borders)."
  [^UiRt rt]
  (when-not (ui/node rt :info-area)
    (let [root (rt/node-by-idx rt 0)]
      (rt/build-child! rt
        {:kind :nine-slice
         :props {:id :info-area-bg :x 179.0 :y 5.0 :w 100.0 :h 177.0
                 :margin 4.0
                 :src "my_mod:textures/guis/blend_quad"
                 :line-tex "my_mod:textures/guis/line"}}
        root)
      (rt/build-child! rt
        {:kind :group
         :props {:id :info-area :x 179.0 :y 5.0 :w 100.0 :h 177.0 :clip? true}}
        root)))
  nil)

(defn- area-node [^UiRt rt]
  (or (ui/node rt :info-area)
      (throw (ex-info "info-area node missing — call ensure-shell! first" {}))))

(defn clear-area!
  "Clear info-area children. Returns fresh build context."
  [^UiRt rt]
  (ensure-shell! rt)
  (rt/clear-children! rt (area-node rt))
  {:rt rt :seq (atom 0) :y (atom 0.0)})

(defn- next-id! [ctx] (keyword (str "ia-" (swap! (:seq ctx) inc))))

(defn- advance! [ctx & [dy]] (swap! (:y ctx) + (or dy row-h)) @(:y ctx))

(defn add-sepline!
  [ctx label]
  (let [^UiRt rt (:rt ctx)
        y @(:y ctx)
        id (next-id! ctx)
        spec {:kind :text
              :props {:id id :x 6.0 :y y :w 98.0 :h 8.0
                      :text (str "-- " label " --")
                      :font-size 6.0 :color 0x99FFFFFF}}]
    (rt/build-child! rt spec (area-node rt))
    (advance! ctx 11.0)))

(defn- wire-editable-value!
  [^UiRt rt ^INode value-n editable? on-change color-change?]
  (when editable?
    (let [last-confirmed (volatile! nil)]
      (rt/register-event! rt (.getIdx value-n) :confirm-input
        (fn [_ _ evt]
          (let [v (:value evt)]
            (when (and on-change (not= v @last-confirmed))
              (vreset! last-confirmed v)
              (on-change v)))
          (when color-change?
            (ui/set-node-prop! rt value-n :color idle-color))))
      ;; Auto-confirm when focus leaves the editable field (click away / tab)
      (rt/register-event! rt (.getIdx value-n) :lost-focus
        (fn [_ node _]
          (let [v (str (or (.getOSlot ^INode node 0) ""))]
            (when (and on-change (not= v @last-confirmed))
              (vreset! last-confirmed v)
              (on-change v)))
          (when color-change?
            (ui/set-node-prop! rt value-n :color idle-color))))
      (when (and editable? color-change?)
        (rt/register-event! rt (.getIdx value-n) :change-content
          (fn [_ _ _]
            (ui/set-node-prop! rt value-n :color edit-color)))))))

(defn add-property!
  "Add label + value row. value may be string or (fn [] string) for live updates.
   Returns {:value-node n} when :editable? true."
  [ctx label value & {:keys [editable? masked? on-change color-change?]
                       :or {color-change? true}}]
  (let [^UiRt rt (:rt ctx)
        y @(:y ctx)
        row-id (next-id! ctx)
        label-id (keyword (str (name row-id) "-label"))
        value-id (keyword (str (name row-id) "-value"))
        value-color (if editable? edit-color idle-color)
        ;; AcademyCraft-style [ ] brackets flanking the editable value
        ;; (upstream: box("[").pos(-4,0), box("]").pos(valueArea.width + 2, 0))
        brackets (when editable?
                   [{:kind :text
                     :props {:id (keyword (str (name row-id) "-lb"))
                             :x (- label-w 6.0) :y 0.0 :w 10.0 :h row-h
                             :text "[" :font-size 8.0 :color idle-color}}
                    {:kind :text
                     :props {:id (keyword (str (name row-id) "-rb"))
                             :x (+ label-w (- 98.0 label-w) 2.0) :y 0.0 :w 10.0 :h row-h
                             :text "]" :font-size 8.0 :color idle-color}}])
        row-spec {:kind :group
                  :props {:id row-id :x 6.0 :y y :w 98.0 :h row-h}
                  :children (vec
                              (concat
                                [{:kind :text
                                  :props {:id label-id :x 0.0 :y 0.0 :w label-w :h row-h
                                          :text (str label) :font-size 8.0 :color 0xFFAAAAAA}}
                                 {:kind :text
                                  :props (cond-> {:id value-id
                                                  :x label-w :y 0.0 :w (- 98.0 label-w) :h row-h
                                                  :text (if (fn? value) (value) (str value))
                                                  :font-size 8.0 :color value-color
                                                  :editable? (boolean editable?)}
                                           masked? (assoc :masked? true))}]
                                (or brackets ())))}
        ^INode row (rt/build-child! rt row-spec (area-node rt))
        ^INode value-n (ui/item-node row value-id)]
    (when (fn? value)
      (let [writer (slot-write/resolve-sig-writer (get node/kinds :text) :text)
            live (sig/computed-o [(rt/clock-ms-sig rt)]
                    (fn [_] (str (value))))
            b (sig/bind! live value-n writer (rt/get-dirty-bindings-q rt))]
        (rt/register-binding! rt (.getIdx value-n) b)))
    (wire-editable-value! rt value-n editable? on-change color-change?)
    (advance! ctx)
    (when editable? {:value-node value-n})))

(defn add-button!
  [ctx label on-click]
  (let [^UiRt rt (:rt ctx)
        y @(:y ctx)
        btn-id (next-id! ctx)
        spec {:kind :text
              :props {:id btn-id :x 6.0 :y y :w 98.0 :h row-h
                      :text label :font-size 8.0 :color 0xFF88CCFF}}]
    (rt/build-child! rt spec (area-node rt))
    (events/on! rt btn-id :left-click (fn [_ _ _] (on-click)))
    (advance! ctx 12.0)))

(def ^:private histogram-tex "my_mod:textures/guis/histogram.png")

(defn- write-box-height!
  "Binding writer: set a box's height to full-h × pct (source ISigD 0..1). With
   :align-h :bottom the box grows UPWARD from its parent's bottom edge — a
   vertical fill bar."
  [^double full-h ^INode node source]
  (let [pct (max 0.0 (min 1.0 (double (.dGet ^cn.li.mcmod.uipojo.signal.ISigD source))))
        h (* full-h pct)]
    (when-not (== h (.getH node))
      (.setH node h)
      (.setFlag node node/FLAG-LAYOUT-DIRTY))))

(defn add-histogram!
  "AcademyCraft-exact histogram (TechUI.histogram + histProperty): the
   guis/histogram.png frame scaled 0.4, one vertical fill-up bar per elem (bar
   value clamped to [0.03,1] like upstream), then a per-elem property row of
   label + colored icon + live value text.
   elems: [{:label :color :value-fn (fn->raw) :max :desc-fn (fn->string)}]."
  [ctx elems]
  (let [^UiRt rt (:rt ctx)
        y @(:y ctx)
        scale 0.4
        bar-h 120.0
        hist-id (next-id! ctx)
        fill-id (fn [idx] (keyword (str (name hist-id) "-b" (long idx))))
        bars (vec (mapcat
                    (fn [idx elem]
                      [{:kind :group
                        :props {:id (keyword (str (name hist-id) "-r" (long idx)))
                                :x (+ 56.0 (* (double idx) 40.0)) :y 78.0 :w 16.0 :h bar-h}
                        :children [{:kind :box
                                    :props {:id (fill-id idx) :x 0.0 :y 0.0 :w 16.0 :h 0.0
                                            :fill (:color elem) :align-h :bottom}}]}])
                    (range) elems))
        frame-spec {:kind :group
                    :props {:id hist-id :x 8.0 :y y :w 210.0 :h 210.0 :scale scale}
                    :children (into [{:kind :image
                                      :props {:id (keyword (str (name hist-id) "-frame"))
                                              :x 0.0 :y 0.0 :w 210.0 :h 210.0 :src histogram-tex}}]
                                    bars)}]
    (rt/build-child! rt frame-spec (area-node rt))
    (doseq [[idx elem] (map-indexed vector elems)]
      (when-let [^INode bar (rt/node-by-id rt (fill-id idx))]
        (let [vf (:value-fn elem)
              cap (max 1.0 (double (:max elem)))
              pct-sig (sig/computed-d [(rt/clock-ms-sig rt)]
                        (fn [_] (max 0.03 (min 1.0 (/ (double (vf)) cap)))))
              b (sig/bind! pct-sig bar (partial write-box-height! bar-h) (rt/get-dirty-bindings-q rt))]
          (rt/register-binding! rt (.getIdx bar) b))))
    (advance! ctx (+ 4.0 (* 210.0 scale)))
    ;; per-elem property row: label + colored icon + live value text (histProperty)
    (doseq [elem elems]
      (let [ry @(:y ctx)
            rid (next-id! ctx)
            val-id (keyword (str (name rid) "-v"))
            df (:desc-fn elem)
            row-spec {:kind :group :props {:id rid :x 6.0 :y ry :w 98.0 :h 8.0}
                      :children
                      [{:kind :text :props {:id (keyword (str (name rid) "-l"))
                                            :x 0.0 :y 0.0 :w 30.0 :h 8.0
                                            :text (:label elem) :font-size 8.0 :color 0xFFCCCCCC}}
                       {:kind :box :props {:id (keyword (str (name rid) "-i"))
                                           :x 33.0 :y 1.0 :w 6.0 :h 6.0 :fill (:color elem)}}
                       {:kind :text :props {:id val-id :x 43.0 :y 0.0 :w 55.0 :h 8.0
                                            :text (str (df)) :font-size 8.0 :color 0xFFFFFFFF}}]}
            ^INode row (rt/build-child! rt row-spec (area-node rt))
            ^INode val-n (ui/item-node row val-id)
            writer (slot-write/resolve-sig-writer (get node/kinds :text) :text)
            live (sig/computed-o [(rt/clock-ms-sig rt)] (fn [_] (str (df))))
            b (sig/bind! live val-n writer (rt/get-dirty-bindings-q rt))]
        (rt/register-binding! rt (.getIdx val-n) b)
        (advance! ctx 10.0)))))

;; Single-bar back-compat shims (auto-info-area callers). Colors/desc match
;; AcademyCraft TechUI.histEnergy / histCapacity.
(defn add-histogram-energy!
  [ctx value-fn max-fn]
  (add-histogram! ctx [{:label "Energy" :color 0xFF25C4FF :value-fn value-fn :max (max-fn)
                        :desc-fn (fn [] (format "%.0f IF" (double (value-fn))))}]))

(defn add-histogram-capacity!
  [ctx load-fn max-capacity]
  (let [mx (max 1.0 (double max-capacity))]
    (add-histogram! ctx [{:label "Load" :color 0xFFFF6C00 :value-fn load-fn :max mx
                          :desc-fn (fn [] (str (long (load-fn)) "/" (long mx)))}])))
