(ns cn.li.ac.gui.info-area-reactive
  "Reactive TechUI info-area — replaces tech-ui-common add-property/add-sepline/add-button."
  (:require [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.node :as node]
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
  "Ensure :info-area group exists on runtime (sibling to machine panel)."
  [^UiRt rt]
  (when-not (ui/node rt :info-area)
    (let [root (rt/node-by-idx rt 0)]
      (rt/build-child! rt
        {:kind :group
         :props {:id :info-area :x 179.0 :y 5.0 :w 110.0 :h 177.0 :clip? true}}
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
    (rt/register-event! rt (.getIdx value-n) :confirm-input
      (fn [_ _ evt]
        (when on-change (on-change (:value evt)))
        (when color-change?
          (ui/set-node-prop! rt value-n :color idle-color))))
    (when (and editable? color-change?)
      (rt/register-event! rt (.getIdx value-n) :change-content
        (fn [_ _ _]
          (ui/set-node-prop! rt value-n :color edit-color))))))

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
        row-spec {:kind :group
                  :props {:id row-id :x 6.0 :y y :w 98.0 :h row-h}
                  :children
                  [{:kind :text
                    :props {:id label-id :x 0.0 :y 0.0 :w label-w :h row-h
                            :text (str label) :font-size 8.0 :color 0xFFAAAAAA}}
                   {:kind :text
                    :props (cond-> {:id value-id
                                    :x label-w :y 0.0 :w (- 98.0 label-w) :h row-h
                                    :text (if (fn? value) (value) (str value))
                                    :font-size 8.0 :color value-color
                                    :editable? (boolean editable?)}
                             masked? (assoc :masked? true))}]}
        ^INode row (rt/build-child! rt row-spec (area-node rt))
        ^INode value-n (ui/item-node row value-id)]
    (when (fn? value)
      (let [writer (get-in node/kinds [:text :prop-writers :text])
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

(defn add-histogram-capacity!
  [ctx load-fn max-capacity]
  (let [^UiRt rt (:rt ctx)
        y @(:y ctx)
        row-id (next-id! ctx)
        prog-id (keyword (str (name row-id) "-bar"))
        cap (max 1.0 (double max-capacity))
        row-spec {:kind :group
                  :props {:id row-id :x 6.0 :y y :w 98.0 :h 12.0}
                  :children
                  [{:kind :text
                    :props {:id (keyword (str (name row-id) "-label"))
                            :x 0.0 :y 0.0 :w 30.0 :h 8.0
                            :text "Load" :font-size 6.0 :color 0xFFCCCCCC}}
                   {:kind :progress
                    :props {:id prog-id :x 32.0 :y 2.0 :w 60.0 :h 6.0 :progress 0.0}}]}
        ^INode row (rt/build-child! rt row-spec (area-node rt))
        ^INode prog (ui/item-node row prog-id)
        progress-sig (sig/computed-d [(rt/clock-ms-sig rt)]
                       (fn [_]
                         (min 1.0 (/ (double (load-fn)) cap))))
        writer (get-in node/kinds [:progress :prop-writers :progress])
        b (sig/bind! progress-sig prog writer (rt/get-dirty-bindings-q rt))]
    (rt/register-binding! rt (.getIdx prog) b)
    (advance! ctx 14.0)))

(defn add-histogram-energy!
  [ctx value-fn max-fn]
  (add-histogram-capacity! ctx value-fn (max-fn)))
