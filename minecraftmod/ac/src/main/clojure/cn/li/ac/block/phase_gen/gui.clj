(ns cn.li.ac.block.phase-gen.gui
  "CLIENT-ONLY: Phase Generator GUI.

  Parity target: AcademyCraft GuiPhaseGen + ContainerPhaseGen
  - Inventory page texture: ui_phasegen
  - Wireless generator page
  - InfoArea histogram: Energy + IF(liquid) columns
  - Slot rules: liquid in / liquid out / energy output."
  (:require [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.gui.slot-registry :as slot-registry]
            [cn.li.mcmod.platform.item :as pitem]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.mcmod.gui.spec :as gui-reg]
            [cn.li.ac.gui.tech-ui-common :as tech-ui]
            [cn.li.ac.gui.manifest :as gui-manifest]
            [cn.li.ac.wireless.gui.tab :as wireless-tab]
            [cn.li.ac.block.gui.sync :as gui-sync]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.block.phase-gen.schema :as phase-schema]
            [cn.li.ac.block.phase-gen.config :as phase-config]
            [cn.li.mcmod.util.log :as log]))

(def ^:private phase-slot-schema-id :phase-gen)
(def ^:private phase-gui-type :phase-gen)
(def ^:private phase-sync
  (gui-sync/schema-sync-fns phase-schema/phase-gen-schema))

(defn- stack-empty?
  [stack]
  (or (nil? stack)
      (try (boolean (pitem/item-is-empty? stack))
           (catch Exception _ false))))

(defn- stack-id
  [stack]
  (when-not (stack-empty? stack)
    (try (some-> stack pitem/item-get-item pitem/item-get-registry-name str)
         (catch Exception _ nil))))

(defn- phase-liquid-unit?
  [stack]
  (and (not (stack-empty? stack))
       (= (stack-id stack) phase-config/matter-unit-item-id)
       (= (int (try (pitem/item-get-damage stack) (catch Exception _ -1)))
          phase-config/matter-unit-phase-liquid-meta)))

(defn create-container
  [tile player]
  (gui-sync/create-schema-container phase-schema/phase-gen-schema tile player phase-gui-type))

(defn get-slot-count [_container]
  (slot-registry/get-slot-count phase-slot-schema-id))

(defn get-slot-item [container slot-index]
  (common/get-slot-item-be container slot-index))

(defn set-slot-item! [container slot-index item-stack]
  (common/set-slot-item-be! container slot-index item-stack {:inventory [nil nil nil]} identity))

(defn slot-changed! [_container _slot-index] nil)

(defn can-place-item?
  [_container slot-index item-stack]
  (case (int slot-index)
    0 (phase-liquid-unit? item-stack)
    ;; Slot 1 is output-only (empty matter units from machine processing).
    1 false
    2 (energy/is-energy-item-supported? item-stack)
    false))

(defn still-valid? [_container _player] true)

(def sync-to-client! (:sync-to-client! phase-sync))
(def get-sync-data (:get-sync-data phase-sync))
(def apply-sync-data! (:apply-sync-data! phase-sync))

(defn tick! [container]
  (sync-to-client! container))

(defn handle-button-click! [_container _button-id _player] nil)

(def on-close (:on-close phase-sync))

(defn- add-panel-text!
  [parent x y w h text color scale]
  (let [widget (cgui-core/create-widget :pos [x y] :size [w h])
        tb (comp/text-box :text text :color color :scale scale)]
    (comp/add-component! widget tb)
    (cgui-core/add-widget! parent widget)
    {:widget widget :text-box tb}))

(defn- attach-panel-readouts!
  [inv-window container]
  ;; Do not edit XML; add a thin runtime overlay to match legacy PhaseGen feel.
  ;; Left side: liquid/tank. Right side: energy/buffer. Near slots: input/output hints.
  (let [liquid-label (add-panel-text! inv-window 15 31 56 8 "LIQUID" 0xD0D6C8FF 0.75)
        liquid-val (add-panel-text! inv-window 15 39 68 8 "0 / 8000 mB" 0xFFFFFFFF 0.78)
        energy-label (add-panel-text! inv-window 96 31 58 8 "ENERGY" 0xD0AEEBFF 0.75)
        energy-val (add-panel-text! inv-window 96 39 70 8 "0 / 6000 IF" 0xFFFFFFFF 0.78)
        in-hint (add-panel-text! inv-window 38 23 22 8 "IN" 0xA0FFFFFF 0.7)
        out-hint (add-panel-text! inv-window 106 61 26 8 "OUT" 0xA0FFFFFF 0.7)
        bat-hint (add-panel-text! inv-window 35 89 30 8 "BAT" 0xA0FFFFFF 0.7)]
    (doseq [hint [(:widget in-hint) (:widget out-hint) (:widget bat-hint)]]
      (events/on-frame hint (fn [_] nil)))
    (events/on-frame (:widget liquid-val)
      (fn [_]
        (let [liq (int (or @(:liquid-amount container) 0))
              cap (int (max 1 (or @(:tank-size container) (phase-config/tank-size))))]
          (comp/set-text! (:text-box liquid-val) (str liq " / " cap " mB")))))
    (events/on-frame (:widget energy-val)
      (fn [_]
        (let [e (int (double (or @(:energy container) 0.0)))
              cap (int (max 1.0 (double (or @(:max-energy container) (phase-config/max-energy)))))]
          (comp/set-text! (:text-box energy-val) (str e " / " cap " IF")))))
    (events/on-frame (:widget liquid-label)
      (fn [_]
        (comp/set-text! (:text-box liquid-label)
                        (if (= (str (or @(:status container) "IDLE")) "GENERATING")
                          "LIQUID*"
                          "LIQUID"))))
    (events/on-frame (:widget energy-label)
      (fn [_]
        (comp/set-text! (:text-box energy-label)
                        (if (= (str (or @(:status container) "IDLE")) "GENERATING")
                          "ENERGY+"
                          "ENERGY"))))
    nil))

(defn create-screen
  [container minecraft-container _player]
  (sync-to-client! container)
  (let [inv-page (tech-ui/create-inventory-page "phasegen")
        wireless-window (wireless-tab/create-wireless-panel {:role :generator :container container})
        pages [inv-page {:id "wireless" :window wireless-window}]
        max-e (fn [] (max 1.0 (double (or @(:max-energy container) (phase-config/max-energy)))))
        max-liquid (fn [] (max 1.0 (double (or @(:tank-size container) (phase-config/tank-size)))))]
    (tech-ui/create-tech-screen-container
      {:pages pages
       :container container
       :minecraft-container minecraft-container
       :bind! (fn [_]
                (attach-panel-readouts! (:window inv-page) container))
       :build-info-area!
       (fn [info-area]
         (let [y0 (tech-ui/add-histogram
                    info-area
                    [(tech-ui/hist-buffer (fn [] (double (or @(:energy container) 0.0))) max-e)
                     {:label "IF"
                      :color 0xFFB983FB
                      :value-fn (fn [] (/ (double (or @(:liquid-amount container) 0)) (max-liquid)))
                      :desc-fn (fn [] (str (int (or @(:liquid-amount container) 0)) " mB"))}]
                    0)
               y1 (tech-ui/add-sepline info-area "Phase Generator" y0)]
           (tech-ui/add-property info-area "status" (fn [] (str (or @(:status container) "IDLE"))) y1)))})))

(defn- phase-container?
  [container]
  (and (map? container)
       (= (:container-type container) phase-gui-type)
       (contains? container :tile-entity)
       (contains? container :energy)
       (contains? container :liquid-amount)))

(defonce-guard phase-gui-installed?)

(defn init-phase-gen-gui!
  []
  (with-init-guard phase-gui-installed?
    ;; Original coordinates from ContainerPhaseGen:
    ;; liquid-in(45,12), liquid-out(112,51), output-energy(42,80)
    (slot-schema/register-slot-schema!
      {:schema-id phase-slot-schema-id
       :slots [{:id :liquid-in :type :phase-input :x 45 :y 12}
               {:id :liquid-out :type :phase-output :x 112 :y 51}
               {:id :output-energy :type :energy :x 42 :y 80}]})
    (gui-reg/register-block-gui!
      (gui-manifest/gui-name :phase-gen)
      (merge (gui-manifest/gui-registration :phase-gen)
             {:container-predicate phase-container?
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
    (log/info "Phase Generator GUI initialized")))
