(ns cn.li.ac.block.solar-gen.gui-reactive
  "Solar Generator container registration through Presentation Runtime."
  (:require [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.gui.spec :as gui-reg]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.gui.manifest :as gui-manifest]
            [cn.li.ac.block.gui.sync :as gui-sync]
            [cn.li.ac.gui.presentation-container :as presentation-container]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.block.solar-gen.schema :as solar-schema]))

(def solar-gen-id :solar-gen)
(def ^:private solar-sync (gui-sync/schema-sync-fns solar-schema/unified-solar-schema))
(defn create-container [tile player]
  (assoc (gui-sync/create-schema-container solar-schema/unified-solar-schema tile player :solar
                                           {:gui-id (gui-manifest/gui-id :solar-gen)})
         :presentation-close-fn (:on-close solar-sync)))
(defn get-slot-count [_] (slot-schema/tile-slot-count solar-gen-id))
(defn can-place-item? [_ _ s] (energy/is-energy-item-supported? s))
(defn get-slot-item [c i] (common/get-slot-item-be c i))
(defn set-slot-item! [c i s] (common/set-slot-item-be! c i s {:inventory [nil]} identity))
(defn slot-changed! [_ _] nil)
(defn still-valid? [_ _] true)
(def server-menu-sync! (:server-menu-sync! solar-sync))
(def on-close (:on-close solar-sync))
(defn handle-button-click! [_ _ _] nil)

(defn create-screen [container menu player]
  (presentation-container/presentation-screen-data
    container menu player solar-gen-id "academy:machine_container"))

(defn- solar-container? [c]
  (and (map? c) (contains? c :tile-entity) (contains? c :energy) (contains? c :status)))

(defn init-solar-reactive! []
  (install/framework-once! ::solar-gui-reactive-installed?
    (fn []
      (slot-schema/register-slot-schema!
        {:schema-id solar-gen-id :slots [{:id :energy :type :energy :x 42 :y 81}]})
      (gui-reg/register-block-gui!
        (gui-manifest/gui-name :solar-gen)
        (merge (gui-manifest/gui-registration :solar-gen)
               {:container-predicate solar-container?
                :container-fn create-container :screen-fn create-screen
                :server-menu-sync-fn server-menu-sync! :validate-fn still-valid?
                :close-fn on-close :button-click-fn handle-button-click!
                :slot-count-fn get-slot-count :slot-get-fn get-slot-item
                :slot-set-fn set-slot-item! :slot-can-place-fn can-place-item?
                :slot-changed-fn slot-changed!}))
      (log/info "Solar Generator GUI initialized (Presentation Runtime)"))))
