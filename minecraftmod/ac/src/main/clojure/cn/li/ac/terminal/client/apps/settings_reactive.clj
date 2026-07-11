(ns cn.li.ac.terminal.client.apps.settings-reactive
  "Complete reactive replacement for settings.clj."
  (:require [cn.li.ac.ability.config :as ability-config]
            [cn.li.ac.config.common :as config-common]
            [cn.li.ac.config.gameplay :as gameplay-config]
            [cn.li.ac.tutorial.config :as tutorial-config]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.platform.config-persist :as config-persist]
            [cn.li.mcmod.i18n :as i18n]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.events :as events]
            [cn.li.mcmod.ui.node :as node]
            [cn.li.mcmod.ui.slot-write :as slot-write]
            [cn.li.mcmod.ui.xml :as ui-xml])
  (:import [cn.li.mcmod.ui.node INode]))

(declare populate-settings-list!)

(def ^:private check-tex-true
  (modid/asset-path "textures/guis" "check_true.png"))

(def ^:private check-tex-false
  (modid/asset-path "textures/guis" "check_false.png"))

(def ^:private row-h 70.0)
(def ^:private visible-h 720.0)

(def ^:private props
  [{:key :attack-player  :category "generic" :get ability-config/attack-player-enabled?    :domain config-common/ability-domain  :sp-only? true}
   {:key :destroy-blocks :category "generic" :get ability-config/destroy-blocks-enabled?   :domain config-common/ability-domain  :sp-only? true}
   {:key :heads-or-tails :category "generic" :get tutorial-config/heads-or-tails-enabled?  :domain config-common/tutorial-domain :sp-only? false}
   {:key :use-mouse-wheel :category "generic" :get gameplay-config/use-mouse-wheel-enabled? :domain config-common/gameplay-domain :sp-only? false}])

(defn- persist! [domain key value] (config-persist/persist-config-value! domain key value))
(defn- toggle-config! [p] (let [v (not ((:get p)))] (persist! (:domain p) (:key p) v) v))

(defn- checkbox-text [p]
  (or (i18n/translate (str "ac.settings.prop." (name (:key p))))
      (name (:key p))))

(defn- hide-row-sections! [^INode item]
  (doseq [id ["cathead-line" "cathead-text" "checkbox-row" "key-row" "restore-row"]]
    (when-let [^INode n (ui/item-node item id)]
      (.setVisible n false))))

(defn- show-only! [^INode item section-id]
  (hide-row-sections! item)
  (when-let [^INode n (ui/item-node item section-id)]
    (.setVisible n true)))

(defn- update-checkbox-item! [r item p checked?]
  (show-only! item :checkbox-row)
  (let [text-n (ui/item-node item :text)
        box-n (ui/item-node item :box)]
    (ui/set-node-prop! r text-n :text (checkbox-text p))
    (ui/set-node-prop! r box-n :src (if checked? check-tex-true check-tex-false))))

(defn- wire-checkbox-click! [r item p]
  (let [^INode box (ui/item-node item :box)]
    (rt/register-event! r (.getIdx box) :left-click
      (fn [_ _ _]
        (let [new-val (toggle-config! p)]
          (update-checkbox-item! r item p new-val))))))

(defn- wire-cathead-item! [r item label]
  (show-only! item :cathead-text)
  (ui/set-node-prop! r (ui/item-node item :cathead-text) :text label)
  (when-let [^INode line (ui/item-node item :cathead-line)]
    (.setVisible line true)))

(defn- all-settings-rows []
  (into []
    (concat
      (mapcat (fn [[cat cat-props]]
                (cons {:type :cathead :label cat}
                      (map #(assoc % :type :checkbox) cat-props)))
              (group-by :category props))
      [{:type :key-binding} {:type :restore}])))

(defn- wire-key-binding-item! [r item key-binding-text recording? current-key]
  (show-only! item :key-row)
  (ui/set-node-prop! r (ui/item-node item :key-label) :text "Open Terminal")
  (let [^INode key-val (ui/item-node item :key-value)
        writer (slot-write/resolve-sig-writer (get node/kinds :text) :text)
        b (sig/bind! key-binding-text key-val writer (rt/get-dirty-bindings-q r))]
    (rt/register-binding! r (.getIdx key-val) b)
    (rt/register-event! r (.getIdx key-val) :left-click
      (fn [_ _ _]
        (reset! recording? true)
        (sig/sset-o! key-binding-text "PRESS...")))
    (rt/register-event! r (.getIdx key-val) :key
      (fn [_ _ evt]
        (when @recording?
          (reset! recording? false)
          (let [kn (or (:key-name evt) (str "KEY_" (:key-code evt)))]
            (reset! current-key kn)
            (sig/sset-o! key-binding-text kn)))))))

(defn- wire-restore-item! [r item key-binding-text recording? current-key]
  (show-only! item :restore-row)
  (ui/set-node-prop! r (ui/item-node item :restore-label) :text "Restore Defaults")
  (ui/set-node-prop! r (ui/item-node item :restore-button) :text "Reset")
  (let [^INode btn (ui/item-node item :restore-button)]
    (rt/register-event! r (.getIdx btn) :left-click
      (fn [_ _ _]
        (doseq [p props] (persist! (:domain p) (:key p) false))
        (populate-settings-list! r (all-settings-rows) key-binding-text recording? current-key)
        (log/info "Settings restored defaults")))))

(defn- populate-settings-list! [r rows key-binding-text recording? current-key]
  (ui/list-set! r "settings-list" rows
    (fn [rt item row]
      (case (:type row)
        :cathead (wire-cathead-item! rt item (:label row))
        :checkbox (do (update-checkbox-item! rt item row ((:get row)))
                      (wire-checkbox-click! rt item row))
        :key-binding (wire-key-binding-item! rt item key-binding-text recording? current-key)
        :restore (wire-restore-item! rt item key-binding-text recording? current-key)
        nil))))

(defn create-runtime []
  (let [r (rt/create-runtime)
        rows (all-settings-rows)
        _ (rt/build! r (ui-xml/load-spec (modid/namespaced-path "guis/new/settings.xml")))
        scroll (sig/signal-d 0.0)
        max-scroll (max 0.0 (- (* (count rows) row-h) visible-h))
        scroll-px (sig/computed-d [scroll]
                     (fn [_] (* (sig/sget-d scroll) max-scroll)))
        _ (rt/put-user-signal! r :scroll scroll)
        key-binding-text (sig/signal-o "LMENU")
        recording? (atom false)
        current-key (atom "LMENU")]
    (ui/bind! r :settings-list :scroll-offset scroll-px)
    (populate-settings-list! r rows key-binding-text recording? current-key)
    (events/on! r :scrollbar :mouse-scroll
      (fn [_ _ evt]
        (sig/sset-d! scroll (max 0.0 (min 1.0 (+ (sig/sget-d scroll) (* (:delta evt) 0.01)))))))
    r))

(defn open! []
  (let [r (create-runtime)]
    (bridge/open-reactive-screen! r "Settings")))
