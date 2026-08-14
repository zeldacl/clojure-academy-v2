(ns cn.li.ac.block.imag-fusor.gui-reactive
  "Reactive GUI registration for the Imag Fusor."
  (:require [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.gui.spec :as gui-reg] [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.platform.item :as pitem] [cn.li.ac.energy.operations :as energy]
            [cn.li.mcmod.util.log :as log] [cn.li.ac.gui.manifest :as gui-manifest]
            [cn.li.ac.block.gui.sync :as gui-sync]
            [cn.li.ac.gui.presentation-container :as presentation-container]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.wireless.gui.container.move :as move-common]
            [cn.li.ac.block.imag-fusor.config :as cfg]
            [cn.li.ac.block.imag-fusor.recipes :as recipes]
            [cn.li.ac.block.imag-fusor.schema :as fusor-schema]))

(def fusor-gui-type :imag-fusor) (def fusor-slot-schema-id :imag-fusor)
(def ^:private fusor-sync (gui-sync/schema-sync-fns fusor-schema/imag-fusor-schema))
(defn- phase-liquid-unit? [stack] (and stack (= (recipes/item-id-from-stack stack) cfg/matter-unit-item-id)))
(defn- create-container [tile player] (assoc (gui-sync/create-schema-container fusor-schema/imag-fusor-schema tile player fusor-gui-type {:gui-id (gui-manifest/gui-id :imag-fusor)}) :presentation-close-fn (:on-close fusor-sync)))
(defn- get-slot-count [_] (slot-schema/tile-slot-count fusor-slot-schema-id))
(defn- get-slot-item [c i] (common/get-slot-item-be c i))
(defn- set-slot-item! [c i s] (common/set-slot-item-be! c i s {:inventory [nil]} identity))
;; Slot indices mirror upstream TileImagFusor: 0 crystal input, 1 crystal
;; output, 2 phase-liquid unit input, 3 energy item, 4 empty-unit output.
;; The GUI previously registered 4 slots in a different order, so the input
;; slot actually fed the crystal OUTPUT index and the liquid input was
;; unreachable (its slot was marked output).
(defn- crystal-input? [s] (boolean (recipes/find-recipe s)))
(defn- can-place-item? [_ i s]
  (case (int i)
    0 (crystal-input? s)
    1 false
    2 (phase-liquid-unit? s)
    3 (energy/is-energy-item-supported? s)
    4 false
    false))
(defn- still-valid? [_ _] true) (def server-menu-sync! (:server-menu-sync! fusor-sync))
(def on-close (:on-close fusor-sync)) (defn- handle-button-click! [_ _ _] nil)
(def ^:private inventory-pred (fn [i s] (>= i s)))
(def ^:private quick-move-config (delay (slot-schema/build-quick-move-config fusor-slot-schema-id {:inventory-pred inventory-pred :rules [{:accept? crystal-input? :slot-ids [:crystal-input]} {:accept? phase-liquid-unit? :slot-ids [:imag-input]} {:accept? energy/is-energy-item-supported? :slot-ids [:energy]}]})))
(defn- quick-move-stack [c i s] (move-common/quick-move-with-rules c i s @quick-move-config))

(defn create-screen [container menu player]
  (presentation-container/presentation-screen-data
    container menu player fusor-slot-schema-id "academy:machine_container"))

(defn- fusor-container? [c] (and (map? c) (= (:container-type c) fusor-gui-type) (contains? c :tile-entity) (contains? c :energy)))
(defn init-imag-fusor-reactive! []
  (install/framework-once! ::imag-fusor-reactive-installed?
  (fn []
    ;; Slot positions match upstream ContainerImagFusor (vanilla container
    ;; coords on the ui_imagfusor texture): crystal in (13,49), crystal out
    ;; (143,49), liquid in (13,10), energy (42,80), empty unit out (143,10).
    ;; :can-place restricts mayPlace at the slot level (upstream SlotCrystal /
    ;; SlotMatterUnit) — without it a crystal could enter the liquid slot,
    ;; where the machine logic then consumed it.
    (slot-schema/register-slot-schema! {:schema-id fusor-slot-schema-id :slots [{:id :crystal-input :type :input :x 13 :y 49 :can-place crystal-input?} {:id :crystal-output :type :output :x 143 :y 49} {:id :imag-input :type :input :x 13 :y 10 :can-place phase-liquid-unit?} {:id :energy :type :energy :x 42 :y 80} {:id :imag-output :type :output :x 143 :y 10}]})
    (gui-reg/register-block-gui! (gui-manifest/gui-name :imag-fusor) (merge (gui-manifest/gui-registration :imag-fusor) {:container-predicate fusor-container? :container-fn create-container :screen-fn create-screen :server-menu-sync-fn server-menu-sync! :validate-fn still-valid? :close-fn on-close :button-click-fn handle-button-click! :slot-count-fn get-slot-count :slot-get-fn get-slot-item :slot-set-fn set-slot-item! :slot-can-place-fn can-place-item? :slot-changed-fn (fn [_ _] nil) :quick-move-fn quick-move-stack}))
    (log/info "Imag Fusor GUI initialized (reactive)"))))
