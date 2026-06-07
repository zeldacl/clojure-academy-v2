(ns cn.li.ac.block.wind-gen.gui
  "CLIENT-ONLY: Wind Generator GUI (main + base)."
  (:require [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.gui.spec :as gui-reg]
            [cn.li.mcmod.platform.item :as item]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.ac.gui.tech-ui-common :as tech-ui]
            [cn.li.ac.gui.manifest :as gui-manifest]
            [cn.li.ac.block.gui.sync :as gui-sync]
            [cn.li.ac.block.wind-gen.schema :as wind-schema]
            [cn.li.ac.wireless.gui.tab :as wireless-tab]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.mcmod.util.log :as log]))

(def ^:private main-schema-id :wind-gen-main)
(def ^:private base-schema-id :wind-gen-base)
(def ^:private main-sync-fns
  (gui-sync/schema-sync-fns wind-schema/wind-gen-main-schema))
(def ^:private base-sync-fns
  (gui-sync/schema-sync-fns wind-schema/wind-gen-base-schema))

(defn- fan-item-stack? [stack]
  (when (and stack (not (item/item-is-empty? stack)))
    (let [rn (try (some-> stack item/item-get-item item/item-get-registry-name) (catch Exception _ nil))
          s (str rn)]
      (or (= rn "windgen_fan") (= rn "my_mod:windgen_fan") (.endsWith s ":windgen_fan")))))

(defn- create-main-container [tile player]
  (gui-sync/create-schema-container wind-schema/wind-gen-main-schema
                                    tile
                                    player
                                    :wind-gen-main
                                    {:gui-id (gui-manifest/gui-id :wind-gen-main)}))

(defn- main-slot-count [_] (slot-schema/tile-slot-count main-schema-id))
(defn- main-get-slot [container slot] (common/get-slot-item-be container slot))
(defn- main-set-slot! [container slot stack]
  (common/set-slot-item-be! container slot stack {:inventory [nil]} identity))
(defn- main-can-place? [_ _slot stack] (boolean (fan-item-stack? stack)))
(defn- main-still-valid? [_ _player] true)

(def ^:private main-server-menu-sync! (:server-menu-sync! main-sync-fns))

(defn- create-main-screen [container minecraft-container _player]
  (let [inv-page (tech-ui/create-inventory-page "windmain")
        pages [inv-page]
        info (fn [info-area]
               (let [y0 (tech-ui/add-sepline info-area "Info" 0)
                     y1 (tech-ui/add-property info-area "fan" (fn [] (if @(:fan-installed container) "YES" "NO")) y0)]
                 (tech-ui/add-property info-area "obstacle" (fn [] (if @(:no-obstacle container) "CLEAR" "BLOCKED")) y1)))]
    (tech-ui/create-tech-screen-container
      {:pages pages
       :container container
       :minecraft-container minecraft-container
       :build-info-area! info})))

(defn- main-container? [container] (= (:container-type container) :wind-gen-main))

(defn- create-base-container [tile player]
  (gui-sync/create-schema-container wind-schema/wind-gen-base-schema
                                    tile
                                    player
                                    :wind-gen-base
                                    {:gui-id (gui-manifest/gui-id :wind-gen-base)}))

(defn- base-slot-count [_] (slot-schema/tile-slot-count base-schema-id))
(defn- base-get-slot [container slot] (common/get-slot-item-be container slot))
(defn- base-set-slot! [container slot stack]
  (common/set-slot-item-be! container slot stack {:inventory [nil]} identity))
(defn- base-can-place? [_ _slot stack] (boolean (energy/is-energy-item-supported? stack)))
(defn- base-still-valid? [_ _player] true)

(def ^:private base-server-menu-sync! (:server-menu-sync! base-sync-fns))

(defn- create-base-screen [container minecraft-container _player]
  (let [inv-page (tech-ui/create-rework-page "guis/rework/page_windbase.xml")
        wireless-window (wireless-tab/create-wireless-panel {:role :generator
                                                             :container container
                                                             :menu minecraft-container})
        pages [inv-page {:id "wireless" :window wireless-window}]
        max-e (fn [] (max 1.0 (double @(:max-energy container))))
        info (fn [info-area]
               (let [y0 (tech-ui/add-histogram info-area [(tech-ui/hist-buffer (fn [] (double @(:energy container))) (max-e))] 0)
                     y1 (tech-ui/add-sepline info-area "Info" y0)
                     y2 (tech-ui/add-property info-area "gen_speed" (fn [] (format "%.2fIF/T" (double @(:gen-speed container)))) y1)]
                 (tech-ui/add-property info-area "status" (fn [] @(:status container)) y2)))]
    (tech-ui/create-tech-screen-container
      {:pages pages
       :container container
       :minecraft-container minecraft-container
       :build-info-area! info})))

(defn- base-container? [container] (= (:container-type container) :wind-gen-base))

(defonce-guard wind-gui-installed?)

(defn init-wind-gen-gui!
  []
  (with-init-guard wind-gui-installed?
    (slot-schema/register-slot-schema! {:schema-id main-schema-id :slots [{:id :fan :type :standard :x 78 :y 9}]})
    (slot-schema/register-slot-schema! {:schema-id base-schema-id :slots [{:id :energy :type :energy :x 42 :y 80}]})

    (gui-reg/register-block-gui!
      (gui-manifest/gui-name :wind-gen-main)
      (merge (gui-manifest/gui-registration :wind-gen-main)
             {:container-predicate main-container?
          :container-fn create-main-container
          :screen-fn create-main-screen
          :server-menu-sync-fn main-server-menu-sync!
          :validate-fn main-still-valid?
          :close-fn (:on-close main-sync-fns)
          :slot-count-fn main-slot-count
          :slot-get-fn main-get-slot
          :slot-set-fn main-set-slot!
          :slot-can-place-fn main-can-place?
              :slot-changed-fn (fn [_ _] nil)}))

    (gui-reg/register-block-gui!
      (gui-manifest/gui-name :wind-gen-base)
      (merge (gui-manifest/gui-registration :wind-gen-base)
             {:container-predicate base-container?
              :container-fn create-base-container
              :screen-fn create-base-screen
              :server-menu-sync-fn base-server-menu-sync!
              :validate-fn base-still-valid?
              :close-fn (:on-close base-sync-fns)
              :slot-count-fn base-slot-count
              :slot-get-fn base-get-slot
              :slot-set-fn base-set-slot!
              :slot-can-place-fn base-can-place?
              :slot-changed-fn (fn [_ _] nil)}))

    (log/info "Wind Generator GUIs initialized (main/base)")))