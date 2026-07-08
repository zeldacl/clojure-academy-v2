(ns cn.li.ac.ability.client.screens.preset-editor-reactive
  "Reactive Preset Editor — native node tree + signal bindings.
   State and server requests remain in preset-editor."
  (:require [cn.li.ac.ability.client.screens.preset-editor :as logic]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.events :as events]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.signal :as sig])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]
           [cn.li.mcmod.ui.node INode]))

(declare refresh-ui!)

(def ^:private panel-w 340.0)
(def ^:private panel-h 250.0)
(def ^:private skill-row-h 22.0)
(def ^:private skill-visible-h 140.0)

(def ^:private color-tab-selected 0xFF4C6FFF)
(def ^:private color-tab-idle 0xFF333333)
(def ^:private color-slot-bg 0xFF252525)
(def ^:private color-skill-idle 0xFF202020)
(def ^:private color-skill-selected 0xFF2E6B2E)
(def ^:private color-save-active 0xFF4A8F4A)
(def ^:private color-save-idle 0xFF444444)
(def ^:private color-active-btn 0xFF444488)

(def ^:private slot-template
  {:kind :group
   :props {:h 25.0 :w 100.0}
   :children [{:kind :box :id :bg
               :props {:x 0.0 :y 0.0 :w 100.0 :h 20.0 :fill color-slot-bg}}
              {:kind :text :id :label
               :props {:x 4.0 :y 6.0 :w 90.0 :h 14.0 :text ""
                       :font-size 10.0 :color 0xFFFFFFFF}}]})

(def ^:private skill-template
  {:kind :group
   :props {:h skill-row-h :w 150.0}
   :children [{:kind :box :id :bg
               :props {:x 0.0 :y 0.0 :w 150.0 :h 20.0 :fill color-skill-idle}}
              {:kind :text :id :label
               :props {:x 4.0 :y 6.0 :w 140.0 :h 14.0 :text ""
                       :font-size 10.0 :color 0xFFFFFFFF}}]})

(def ^:private ui-spec
  {:kind :group :id :root
   :props {:w panel-w :h panel-h :align-w :center :align-h :center}
   :children
   [{:kind :text :id :title
     :props {:x 10.0 :y 2.0 :w 200.0 :h 16.0 :text "Preset Editor"
             :font-size 12.0 :color 0xFFFFFFFF}}
    (for [idx (range 4)]
      {:kind :box :id (keyword (str "ptab-" idx))
       :props {:x (+ 10.0 (* idx 45.0)) :y 10.0 :w 40.0 :h 20.0
               :fill color-tab-idle :hover-tint 0x33FFFFFF}
       :children [{:kind :text :id (keyword (str "ptab-lbl-" idx))
                   :props {:x 10.0 :y 6.0 :w 30.0 :h 14.0
                           :text (str "P" (inc idx))
                           :font-size 10.0 :color 0xFFFFFFFF}}]})
    {:kind :list :id :slot-list
     :props {:x 10.0 :y 40.0 :w 100.0 :h 100.0 :template slot-template :spacing 5.0}}
    {:kind :list :id :skill-list
     :props {:x 170.0 :y 40.0 :w 150.0 :h skill-visible-h
             :template skill-template :spacing 2.0}}
    {:kind :box :id :save-btn
     :props {:x 10.0 :y 200.0 :w 80.0 :h 20.0 :fill color-save-idle :hover-tint 0x33FFFFFF}
     :children [{:kind :text :id :save-lbl
                 :props {:x 25.0 :y 6.0 :w 40.0 :h 14.0 :text "Save"
                         :font-size 10.0 :color 0xFFFFFFFF}}]}
    {:kind :box :id :active-btn
     :props {:x 100.0 :y 200.0 :w 80.0 :h 20.0 :fill color-active-btn :hover-tint 0x33FFFFFF}
     :children [{:kind :text :id :active-lbl
                 :props {:x 8.0 :y 6.0 :w 70.0 :h 14.0 :text "Set Active"
                         :font-size 10.0 :color 0xFFFFFFFF}}]}]})

(def ^:private active-by-uuid (atom {}))

(defn- owner-key [owner]
  (logic/editor-owner-key owner))

(defn- track-active! [owner ^UiRt r]
  (when-let [uuid (or (:player-uuid owner) (nth (owner-key owner) 2 nil))]
    (swap! active-by-uuid assoc uuid {:rt r :owner owner})))

(defn- untrack-active! [owner]
  (when-let [uuid (or (:player-uuid owner) (nth (owner-key owner) 2 nil))]
    (swap! active-by-uuid dissoc uuid)))

(defn- refresh-preset-tab-labels! [^UiRt r rd]
  (let [selected (:selected-preset rd)
        active (:active-preset rd)]
    (doseq [idx (range 4)]
      (when-let [^INode tab (ui/node r (keyword (str "ptab-" idx)))]
        (ui/set-node-prop! r tab :fill (if (= idx selected) color-tab-selected color-tab-idle)))
      (when-let [^INode lbl (ui/node r (keyword (str "ptab-lbl-" idx)))]
        (ui/set-node-prop! r lbl :text (str "P" (inc idx) (when (= idx active) "*")))))))

(defn- wire-preset-tabs! [^UiRt r owner]
  (doseq [idx (range 4)]
    (when-let [^INode tab (ui/node r (keyword (str "ptab-" idx)))]
      (rt/register-event! r (.getIdx tab) :left-click
        (fn [_ _ _]
          (logic/on-preset-tab-click owner idx)
          (refresh-ui! r owner))))))

(defn- populate-slots! [^UiRt r owner rd]
  (let [slots (vec (or (:slots rd) (repeat 4 nil)))]
    (ui/list-set! r :slot-list (range 4)
      (fn [rt item slot-idx]
        (let [slot (nth slots slot-idx nil)
              label (str "Slot " (inc slot-idx) ": " (or (:skill-name slot) "<empty>"))]
          (ui/set-node-prop! rt (ui/item-node item :label) :text label)
          (let [^INode bg (ui/item-node item :bg)]
            (rt/register-event! rt (.getIdx bg) :left-click
              (fn [_ _ _]
                (logic/on-slot-click owner slot-idx)
                (refresh-ui! rt owner)))))))))

(defn- populate-skills! [^UiRt r owner rd]
  (let [skills (vec (:available-skills rd))
        selected (:selected-skill rd)
        skill-count-sig (rt/user-signal r :skill-count)]
    (when skill-count-sig (sig/sset-l! skill-count-sig (count skills)))
    (ui/list-set! r :skill-list skills
      (fn [rt item skill]
        (let [chosen? (= (:skill-id skill) selected)]
          (ui/set-node-prop! rt (ui/item-node item :label) :text (:skill-name skill))
          (ui/set-node-prop! rt (ui/item-node item :bg) :fill
            (if chosen? color-skill-selected color-skill-idle))
          (let [^INode bg (ui/item-node item :bg)]
            (rt/register-event! rt (.getIdx bg) :left-click
              (fn [_ _ _]
                (logic/on-skill-select owner (:skill-id skill))
                (refresh-ui! rt owner)))))))))

(defn- refresh-action-buttons! [^UiRt r rd]
  (when-let [^INode save (ui/node r :save-btn)]
    (ui/set-node-prop! r save :fill (if (:has-changes rd) color-save-active color-save-idle))))

(defn refresh-ui!
  "Rebuild list rows and tab highlights from current editor + player state."
  [^UiRt r owner]
  (if-let [rd (logic/build-preset-editor-render-data owner)]
    (do
      (refresh-preset-tab-labels! r rd)
      (populate-slots! r owner rd)
      (populate-skills! r owner rd)
      (refresh-action-buttons! r rd))
    nil))

(defn refresh-active-screen!
  "Called when server preset data syncs while the editor is open."
  [player-uuid]
  (when-let [{:keys [rt owner]} (get @active-by-uuid player-uuid)]
    (refresh-ui! rt owner)))

(defn- wire-action-buttons! [^UiRt r owner]
  (doseq [[id action]
          [[:save-btn logic/on-save-click]
           [:active-btn logic/on-set-active-click]]]
    (when-let [^INode btn (ui/node r id)]
      (rt/register-event! r (.getIdx btn) :left-click
        (fn [_ _ _]
          (action owner)
          (refresh-ui! r owner))))))

(defn- wire-skill-scroll! [^UiRt r]
  (let [scroll (sig/signal-d 0.0)
        skill-count (sig/signal-l 0)]
    (rt/put-user-signal! r :skill-scroll scroll)
    (rt/put-user-signal! r :skill-count skill-count)
    (ui/bind! r :skill-list :scroll-offset
      (sig/computed-d [scroll skill-count]
        (fn [_]
          (let [n (sig/sget-l skill-count)
                max-scroll (max 0.0 (- (* n (+ skill-row-h 2.0)) skill-visible-h))]
            (* (sig/sget-d scroll) max-scroll)))))
    (events/on! r :skill-list :mouse-scroll
      (fn [_ _ evt]
        (sig/sset-d! scroll (max 0.0 (min 1.0 (+ (sig/sget-d scroll) (* (:delta evt) 0.05)))))))))

(defn create-runtime
  "Build reactive preset editor runtime for owner (map with :player-uuid)."
  [owner]
  (let [r (rt/create-runtime)
        flat-spec (update ui-spec :children
                          (fn [chs]
                            (vec (mapcat #(if (vector? %) % [%]) chs))))]
    (logic/open-screen! owner)
    (rt/build! r flat-spec)
    (track-active! owner r)
    (rt/put-user-signal! r :owner owner)
    (wire-preset-tabs! r owner)
    (wire-action-buttons! r owner)
    (wire-skill-scroll! r)
    (refresh-ui! r owner)
    r))

(defn open-screen!
  ([owner]
   (let [^UiRt r (create-runtime owner)]
     (bridge/open-reactive-screen! r "Preset Editor"
       {:on-close #(do (untrack-active! owner)
                       (logic/close-screen! owner))}))))

(defn on-close!
  [owner]
  (untrack-active! owner)
  (logic/close-screen! owner))
