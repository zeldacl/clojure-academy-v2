(ns cn.li.ac.block.phase-gen.gui-reactive
  "Complete reactive replacement for phase_gen/gui.clj."
  (:refer-clojure :exclude [sync])
  (:require [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.gui.spec :as gui-reg] [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.platform.item :as pitem] [cn.li.ac.energy.operations :as energy]
            [cn.li.mcmod.util.log :as log] [cn.li.ac.gui.manifest :as gui-manifest]
            [cn.li.ac.gui.block-gui-reactive :as bgui]
            [cn.li.mcmod.ui.runtime :as rt] [cn.li.mcmod.ui.signal :as sig]
            [cn.li.ac.block.gui.sync :as gui-sync]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.block.phase-gen.schema :as phase-schema]
            [cn.li.ac.block.phase-gen.config :as phase-config]))

(def ^:private slot-schema-id :phase-gen) (def ^:private gui-type :phase-gen)
(def ^:private sync (gui-sync/schema-sync-fns phase-schema/phase-gen-schema))

(defn- stack-empty? [s] (or (nil? s) (try (pitem/empty? s) (catch Exception _ false))))
(defn- stack-id [s] (when-not (stack-empty? s) (try (some-> s pitem/object pitem/registry-name str) (catch Exception _ nil))))
(defn- phase-liquid-unit? [s] (and (not (stack-empty? s)) (= (stack-id s) phase-config/matter-unit-item-id) (= (int (try (pitem/damage s) (catch Exception _ -1))) phase-config/matter-unit-phase-liquid-meta)))

(defn create-container [tile player] (gui-sync/create-schema-container phase-schema/phase-gen-schema tile player gui-type {:gui-id (gui-manifest/gui-id :phase-gen)}))
(defn get-slot-count [_] (slot-schema/tile-slot-count slot-schema-id))
(defn get-slot-item [c i] (common/get-slot-item-be c i))
(defn set-slot-item! [c i s] (common/set-slot-item-be! c i s {:inventory [nil]} identity))
(defn can-place-item? [_ i s] (case (int i) (0 1) (phase-liquid-unit? s) 2 false 3 (energy/is-energy-item-supported? s) false))
(defn still-valid? [_ _] true) (def server-menu-sync! (:server-menu-sync! sync))
(def on-close (:on-close sync)) (defn handle-button-click! [_ _ _] nil)

(defn- attach-binds! [r container _menu _player _signals]
  (rt/put-user-signal! r :liquid-amount
    (sig/computed-o [(rt/clock-ms-sig r)] (fn [_] (str (or @(:liquid container) 0) " mB")))))

(defn create-screen [container menu player]
  (let [safe-val #(some-> % deref)]
    (bgui/create-screen
      {:page-xml "guis/rework/new/page_phasegen.xml" :texture-name "phasegen"
       :container container :menu menu
       :histograms [(bgui/hist-buffer (fn [] (double @(:energy container))) (fn [] (max 1.0 (double @(:max-energy container)))))]
       :properties {:status (fn [] (or (safe-val (:status container)) "IDLE"))
                    :liquid (fn [] (str (or (safe-val (:liquid container)) 0) " mB"))}
       :wireless? true :wireless-role :generator :custom-bind! attach-binds!})))

(def update! bgui/update-signals!) (def open! bgui/open!)
(defn- container? [c] (and (map? c) (= (:container-type c) gui-type) (contains? c :tile-entity) (contains? c :energy)))
(defn init-phase-gen-reactive! []
  (install/framework-once! ::phase-gen-reactive-installed?
  (fn []
    (slot-schema/register-slot-schema! {:schema-id slot-schema-id :slots [{:id :input-1 :type :input :x 30 :y 20} {:id :input-2 :type :input :x 48 :y 20} {:id :output :type :output :x 120 :y 52} {:id :energy :type :energy :x 42 :y 81}]})
    (gui-reg/register-block-gui! (gui-manifest/gui-name :phase-gen) (merge (gui-manifest/gui-registration :phase-gen) {:container-predicate container? :container-fn create-container :screen-fn create-screen :server-menu-sync-fn server-menu-sync! :validate-fn still-valid? :close-fn on-close :button-click-fn handle-button-click! :slot-count-fn get-slot-count :slot-get-fn get-slot-item :slot-set-fn set-slot-item! :slot-can-place-fn can-place-item? :slot-changed-fn (fn [_ _] nil)}))
    (log/info "Phase Generator GUI initialized (reactive)"))))
