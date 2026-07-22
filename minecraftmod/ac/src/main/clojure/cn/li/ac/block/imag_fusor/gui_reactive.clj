(ns cn.li.ac.block.imag-fusor.gui-reactive
  "Reactive GUI registration for the Imag Fusor."
  (:require [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.gui.spec :as gui-reg] [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.platform.item :as pitem] [cn.li.ac.energy.operations :as energy]
            [cn.li.mcmod.util.log :as log] [cn.li.ac.gui.manifest :as gui-manifest]
            [cn.li.ac.gui.block-gui-reactive :as bgui]
            [cn.li.mcmod.ui.runtime :as rt] [cn.li.mcmod.ui.signal :as sig]
            [cn.li.ac.block.gui.sync :as gui-sync]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.wireless.gui.container.move :as move-common]
            [cn.li.ac.block.imag-fusor.config :as cfg]
            [cn.li.ac.block.imag-fusor.recipes :as recipes]
            [cn.li.ac.block.imag-fusor.schema :as fusor-schema]))

(def fusor-gui-type :imag-fusor) (def fusor-slot-schema-id :imag-fusor)
(def ^:private fusor-sync (gui-sync/schema-sync-fns fusor-schema/imag-fusor-schema))
(defn- phase-liquid-unit? [stack] (and stack (= (recipes/item-id-from-stack stack) cfg/matter-unit-item-id)))
(defn- create-container [tile player] (gui-sync/create-schema-container fusor-schema/imag-fusor-schema tile player fusor-gui-type {:gui-id (gui-manifest/gui-id :imag-fusor)}))
(defn- get-slot-count [_] (slot-schema/tile-slot-count fusor-slot-schema-id))
(defn- get-slot-item [c i] (common/get-slot-item-be c i))
(defn- set-slot-item! [c i s] (common/set-slot-item-be! c i s {:inventory [nil]} identity))
(defn- can-place-item? [_ i s] (case (int i) (0 1) (phase-liquid-unit? s) 2 false 3 (energy/is-energy-item-supported? s) false))
(defn- still-valid? [_ _] true) (def server-menu-sync! (:server-menu-sync! fusor-sync))
(def on-close (:on-close fusor-sync)) (defn- handle-button-click! [_ _ _] nil)
(def ^:private inventory-pred (fn [i s] (>= i s)))
(def ^:private quick-move-config (delay (slot-schema/build-quick-move-config fusor-slot-schema-id {:inventory-pred inventory-pred :rules [{:accept? phase-liquid-unit? :slot-ids [:input-1 :input-2]} {:accept? energy/is-energy-item-supported? :slot-ids [:energy]}]})))
(defn- quick-move-stack [c i s] (move-common/quick-move-with-rules c i s @quick-move-config))

(defn- attach-binds! [r container _menu _player _signals]
  (let [clock (rt/clock-ms-sig r)]
    (rt/put-user-signal! r :work-progress (sig/computed-d [clock] (fn [_] (double (or @(:work-progress container) 0.0)))))
    (rt/put-user-signal! r :imagination-needed (sig/computed-o [clock] (fn [_] (str (or @(:imagination-needed container) 0) " IM"))))))

(defn create-screen [container menu player]
  (let [safe-val #(some-> % deref)]
    (bgui/create-screen
      {:page-xml "guis/rework/new/page_imagfusor.xml" :texture-name "imagfusor"
       :container container :menu menu
       :histograms [(bgui/hist-buffer (fn [] (double @(:energy container))) (fn [] (max 1.0 (double @(:max-energy container)))))
                    (bgui/hist-buffer (fn [] (double (or @(:work-progress container) 0.0))) (fn [] 1.0))]
       :properties {:status (fn [] (or (safe-val (:status container)) "IDLE"))}
       :wireless? true :wireless-role :machine :custom-bind! attach-binds!})))

(def update! bgui/update-signals!) (def open! bgui/open!)
(defn- fusor-container? [c] (and (map? c) (= (:container-type c) fusor-gui-type) (contains? c :tile-entity) (contains? c :energy)))
(defn init-imag-fusor-reactive! []
  (install/framework-once! ::imag-fusor-reactive-installed?
  (fn []
    (slot-schema/register-slot-schema! {:schema-id fusor-slot-schema-id :slots [{:id :input-1 :type :input :x 64 :y 10} {:id :input-2 :type :input :x 112 :y 10} {:id :output :type :output :x 88 :y 60} {:id :energy :type :energy :x 42 :y 80}]})
    (gui-reg/register-block-gui! (gui-manifest/gui-name :imag-fusor) (merge (gui-manifest/gui-registration :imag-fusor) {:container-predicate fusor-container? :container-fn create-container :screen-fn create-screen :server-menu-sync-fn server-menu-sync! :validate-fn still-valid? :close-fn on-close :button-click-fn handle-button-click! :slot-count-fn get-slot-count :slot-get-fn get-slot-item :slot-set-fn set-slot-item! :slot-can-place-fn can-place-item? :slot-changed-fn (fn [_ _] nil) :quick-move-fn quick-move-stack}))
    (log/info "Imag Fusor GUI initialized (reactive)"))))
