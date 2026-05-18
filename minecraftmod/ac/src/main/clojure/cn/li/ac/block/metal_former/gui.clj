(ns cn.li.ac.block.metal-former.gui
  "CLIENT-ONLY: Metal Former GUI"
  (:require [clojure.string :as str]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.gui.spec :as gui-reg]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.gui.slot-registry :as slot-registry]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.gui.manifest :as gui-manifest]
            [cn.li.ac.gui.tech-ui-common :as tech-ui]
            [cn.li.ac.block.gui.sync :as gui-sync]
            [cn.li.ac.block.metal-former.config :as cfg]
            [cn.li.ac.block.metal-former.recipes :as recipes]
            [cn.li.ac.block.metal-former.schema :as former-schema]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.wireless.gui.tab :as wireless-tab]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.energy.operations :as energy]))

(def ^:private former-slot-schema-id :metal-former)
(def ^:private former-gui-type :metal-former)
(def ^:private former-gui-schema
  (mapv (fn [field]
          (if (= (:key field) :mode)
            (assoc field
                   :gui-init (fn [state]
                               (recipes/normalize-mode (get state :mode (:default field))))
                   :gui-coerce recipes/normalize-mode)
            field))
        former-schema/metal-former-schema))
(def ^:private former-sync
  (gui-sync/schema-sync-fns former-gui-schema))

(defn- msg [action]
  (msg-registry/msg former-gui-type action))

(defn- create-container
  [tile player]
  (gui-sync/create-schema-container former-gui-schema tile player former-gui-type))

(defn- get-slot-count [_container]
  (slot-registry/get-slot-count former-slot-schema-id))

(defn- get-slot-item [container slot-index]
  (common/get-slot-item-be container slot-index))

(defn- set-slot-item! [container slot-index item-stack]
  (common/set-slot-item-be! container slot-index item-stack {:inventory [nil]} identity))

(defn- slot-changed! [_container _slot-index] nil)

(defn- can-place-item? [_container slot-index item-stack]
  (let [slot-index (int slot-index)]
    (case slot-index
      2 (energy/is-energy-item-supported? item-stack)
      true)))

(defn- still-valid? [_container _player] true)

(def ^:private sync-to-client! (:sync-to-client! former-sync))

(defn- get-sync-data [container]
  (assoc ((:get-sync-data former-sync) container)
         :mode (recipes/mode->string @(:mode container))))

(def ^:private apply-sync-data! (:apply-sync-data! former-sync))

(defn- tick! [container]
  (gui-sync/sync-tick! container sync-to-client!))

(def ^:private on-close (:on-close former-sync))
(defn- handle-button-click! [_container _button-id _player] nil)

(defn- request-alternate!
  [container dir]
  (let [tile (:tile-entity container)
        payload (assoc (net-helpers/tile-pos-payload tile) :dir (int dir))]
    (net-client/send-to-server
      (msg :alternate)
      payload
      (fn [resp]
        (when-let [mode (:mode resp)]
          (reset! (:mode container) (recipes/normalize-mode mode)))))))

(defn- bind-progress!
  [inv-window container]
  (when-let [widget (cgui-core/find-widget inv-window "progress")]
    (when-let [bar (comp/get-component widget :progressbar)]
      (events/on-frame widget
        (fn [_]
          (let [progress (if @(:working container)
                           (/ (double @(:work-counter container)) (double cfg/work-ticks))
                           0.0)]
            (comp/set-progress! bar (max 0.0 (min 1.0 progress)))))))))

(defn- bind-mode-icon!
  [inv-window container]
  (when-let [widget (cgui-core/find-widget inv-window "icon_mode")]
    (when-let [dt (comp/get-drawtexture-component widget)]
      (events/on-frame widget
        (fn [_]
          (comp/set-texture! dt (recipes/mode->icon-texture @(:mode container))))))))

(defn- bind-buttons!
  [inv-window container]
  (when-let [btn-left (cgui-core/find-widget inv-window "btn_left")]
    (events/on-left-click btn-left
      (fn [_]
        (request-alternate! container -1))))
  (when-let [btn-right (cgui-core/find-widget inv-window "btn_right")]
    (events/on-left-click btn-right
      (fn [_]
        (request-alternate! container 1)))))

(defn- create-screen
  [container minecraft-container _player]
  (sync-to-client! container)
  (let [inv-page (tech-ui/create-rework-page "guis/rework/page_metalformer.xml")
        inv-window (:window inv-page)
        wireless-window (wireless-tab/create-wireless-panel {:role :receiver :container container})
        wireless-page {:id "wireless" :window wireless-window}
        pages [inv-page wireless-page]
        max-e (fn [] (max 1.0 (double @(:max-energy container))))]
    (tech-ui/create-tech-screen-container
      {:pages pages
       :container container
       :minecraft-container minecraft-container
       :bind! (fn [_]
                (bind-progress! inv-window container)
                (bind-mode-icon! inv-window container)
                (bind-buttons! inv-window container))
       :build-info-area!
       (fn [info-area]
         (let [y0 (tech-ui/add-histogram info-area
                                         [(tech-ui/hist-buffer (fn [] (double @(:energy container))) max-e)]
                                         0)
               y1 (tech-ui/add-sepline info-area "Metal Former" y0)
               y2 (tech-ui/add-property info-area "mode"
                                        (fn [] (str/upper-case (recipes/mode->string @(:mode container))))
                                        y1)]
           (tech-ui/add-property info-area "progress"
                                  (fn []
                                    (if @(:working container)
                                      (format "%d/%d" @(:work-counter container) cfg/work-ticks)
                                      "IDLE"))
                                  y2)))})))

(defn- former-container?
  [container]
  (and (map? container)
       (= (:container-type container) former-gui-type)
       (contains? container :tile-entity)
       (contains? container :mode)
       (contains? container :energy)))

(defonce-guard metal-former-gui-installed?)

(defn init-metal-former-gui!
  []
  (with-init-guard metal-former-gui-installed?
    (slot-schema/register-slot-schema!
      {:schema-id former-slot-schema-id
       :slots [{:id :input :type :input :x 13 :y 49}
               {:id :output :type :output :x 143 :y 49}
               {:id :energy :type :energy :x 42 :y 80}]})
    (gui-reg/register-block-gui!
      (gui-manifest/gui-name :metal-former)
      (merge (gui-manifest/gui-registration :metal-former)
             {:container-predicate former-container?
       :container-fn create-container
       :screen-fn create-screen
       :tick-fn tick!
       :sync-get get-sync-data
       :sync-apply apply-sync-data!
       :validate-fn still-valid?
       :close-fn on-close
       :button-click-fn handle-button-click!
       :slot-count-fn get-slot-count
       :slot-get-fn get-slot-item
       :slot-set-fn set-slot-item!
       :slot-can-place-fn can-place-item?
        :slot-changed-fn slot-changed!}))
    (log/info "Metal Former GUI initialized")))
