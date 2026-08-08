(ns cn.li.ac.terminal.client.apps.ui-customize-reactive
  "Reactive port of AcademyCraft's CustomizeUI, opened from Settings > Misc."
  (:require [cn.li.ac.config.common :as config-common]
            [cn.li.ac.config.gameplay :as gameplay]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.config.registry :as config-reg]
            [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.framework.platform :as platform]
            [cn.li.mcmod.i18n :as i18n]
            [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.events :as events]
            [cn.li.mcmod.ui.node :as node]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.xml :as ui-xml])
  (:import [cn.li.mcmod.ui.node INode]))

(def ^:private elements
  [{:id :cpbar :config-key :hud-cpbar-position :preview-id :preview-cpbar}
   {:id :keyhint :config-key :hud-keyhint-position :preview-id :preview-keyhint}
   {:id :media :config-key :hud-media-position :preview-id :preview-media}
   {:id :notification :config-key :hud-notification-position :preview-id :preview-notification}])

(defn- persist-position! [config-key position]
  (config-reg/set-config-value! config-common/gameplay-domain config-key position)
  (when-let [fw-atom (fw/fw-atom)]
    (platform/call-adapter fw-atom :config-persist :persist!
                           config-common/gameplay-domain config-key position)))

(defn- local [suffix]
  (or (i18n/translate (str "gui." modid/MOD-ID ".uiedit." suffix)) suffix))

(defn- base-position [element-id sw sh]
  (case element-id
    :cpbar [(- sw 205.0) 12.0]
    :keyhint [(- sw 78.0) (+ (- (/ sh 2.0) 25.0) 30.0)]
    :media [(- sw 151.0) (- sh 42.0)]
    :notification [0.0 15.0]
    [0.0 0.0]))

(defn- set-preview-position! [r {:keys [id preview-id]} sw sh]
  (let [[base-x base-y] (base-position id sw sh)
        [dx dy] (gameplay/hud-position id)
        ^INode preview (rt/node-by-id r preview-id)]
    (.setX preview (+ base-x dx))
    (.setY preview (+ base-y dy))
    (.setFlag preview node/FLAG-LAYOUT-DIRTY)))

(defn- set-selected-preview! [r selected-id]
  (doseq [{:keys [id]} elements]
    (ui/set-prop! r (keyword (str "preview-" (name id) "-mark")) :fill
                  (if (= id selected-id) 0x55FB8525 0x00FFFFFF))))

(defn- valid-coordinate [value]
  (let [n (Double/parseDouble (str value))]
    (when (or (< n -512.0) (> n 512.0))
      (throw (NumberFormatException. "HUD coordinate is outside [-512, 512]")))
    n))

(defn- edit-position! [r selected axis value sw sh]
  (when-let [{:keys [id config-key] :as element}
             (some #(when (= (:id %) @selected) %) elements)]
    (try
      (let [[x y] (gameplay/hud-position id)
            n (valid-coordinate value)
            position (if (= axis :x) [n y] [x n])]
        (persist-position! config-key position)
        (ui/set-prop! r (if (= axis :x) :edit-x :edit-y) :color 0xFFFFFFFF)
        (set-preview-position! r element sw sh))
      (catch NumberFormatException _
        (ui/set-prop! r (if (= axis :x) :edit-x :edit-y) :color 0xFFFF5555)))))

(defn- select-element! [r selected {:keys [id]}]
  (reset! selected id)
  (let [[x y] (gameplay/hud-position id)]
    (ui/set-prop! r :editbox :visible? true)
    (ui/set-prop! r :edit-x :text (str x))
    (ui/set-prop! r :edit-y :text (str y))
    (ui/set-prop! r :edit-x :color 0xFFFFFFFF)
    (ui/set-prop! r :edit-y :color 0xFFFFFFFF)
    (set-selected-preview! r id)))

(defn create-runtime []
  (let [r (rt/create-runtime)
        [sw sh] (or (bridge/get-window-size) [854 480])
        sw (double sw)
        sh (double sh)
        selected (atom nil)]
    (rt/build! r (ui-xml/load-spec (modid/namespaced-path "guis/new/ui_edit.xml")))
    (ui/set-prop! r :header :text (local "elements"))
    ;; :visible? is a build-time node prop, not a prop-writer — set-prop!
    ;; throws "no prop-writer" on it. Visibility is a node field at runtime.
    ;; (ui_edit.xml already declares visible="false"; this keeps a re-opened
    ;; runtime from inheriting a shown editbox.)
    (.setVisible ^INode (rt/node-by-id r :editbox) false)
    (ui/list-set! r :hud-list elements
      (fn [runtime item element]
        (ui/set-node-prop! runtime (ui/item-node item :label)
                           :text (local (str "elm." (name (:id element)))))
        (rt/register-event! runtime (.getIdx ^INode item) :left-click
          (fn [_ _ _] (select-element! r selected element)))))
    (doseq [element elements]
      (set-preview-position! r element sw sh)
      (events/on! r (:preview-id element) :left-click
        (fn [_ _ _] (select-element! r selected element))))
    (events/on-confirm-input r :edit-x
      (fn [_ _ value] (edit-position! r selected :x value sw sh)))
    (events/on-confirm-input r :edit-y
      (fn [_ _ value] (edit-position! r selected :y value sw sh)))
    r))

(defn open! []
  (bridge/open-reactive-screen! (create-runtime) "Customize UI"))
