(ns cn.li.ac.block.wind-gen.block
  "Wind Generator blocks aligned with AcademyCraft 1.12 semantics."
  (:require [clojure.string :as str]
            [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.block.tile-dsl :as tdsl]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.mcmod.block.state-schema :as state-schema]
            [cn.li.mcmod.platform.capability :as platform-cap]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.item :as item]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.ac.block.role-impls :as impls]
            [cn.li.ac.block.wind-gen.config :as wind-config]
            [cn.li.ac.block.wind-gen.schema :as wind-schema]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.ac.registry.hooks :as hooks]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.config.modid :as modid])
  (:import [cn.li.acapi.wireless IWirelessGenerator]))

(defn- msg [action] (msg-registry/msg :wind-gen action))

(defn- id-path
  [x]
  (let [s (some-> x str str/lower-case)]
    (when (and s (not (str/blank? s)))
      (let [idx (.lastIndexOf s ":")]
        (if (neg? idx) s (subs s (inc idx)))))))

(def main-state-schema (state-schema/filter-server-fields wind-schema/wind-gen-main-schema))
(def base-state-schema (state-schema/filter-server-fields wind-schema/wind-gen-base-schema))
(def pillar-state-schema (state-schema/filter-server-fields wind-schema/wind-gen-pillar-schema))

(def main-default-state (state-schema/schema->default-state main-state-schema))
(def base-default-state (state-schema/schema->default-state base-state-schema))
(def pillar-default-state (state-schema/schema->default-state pillar-state-schema))

(def main-scripted-load-fn (state-schema/schema->load-fn main-state-schema))
(def main-scripted-save-fn (state-schema/schema->save-fn main-state-schema))
(def base-scripted-load-fn (state-schema/schema->load-fn base-state-schema))
(def base-scripted-save-fn (state-schema/schema->save-fn base-state-schema))
(def pillar-scripted-load-fn (state-schema/schema->load-fn pillar-state-schema))
(def pillar-scripted-save-fn (state-schema/schema->save-fn pillar-state-schema))

(def ^:private wind-main-ids #{"wind-gen-main" "wind-gen-main-part" "wind_gen_main" "wind_gen_main_part"})
(def ^:private wind-base-ids #{"wind-gen-base" "wind-gen-base-part" "wind_gen_base" "wind_gen_base_part"})
(def ^:private wind-pillar-ids #{"wind-gen-pillar" "wind_gen_pillar" "windgen_pillar"})
(def ^:private wind-main-controller-ids #{"wind-gen-main" "wind_gen_main"})
(def ^:private wind-main-part-ids #{"wind-gen-main-part" "wind_gen_main_part"})
(def ^:private wind-base-controller-ids #{"wind-gen-base" "wind_gen_base"})
(def ^:private wind-base-part-ids #{"wind-gen-base-part" "wind_gen_base_part"})

(defn- wind-main-id? [x] (contains? wind-main-ids (id-path x)))
(defn- wind-base-id? [x] (contains? wind-base-ids (id-path x)))
(defn- wind-pillar-id? [x] (contains? wind-pillar-ids (id-path x)))

(defn- sub-id-zero? [be]
  (zero? (long (get (or (platform-be/get-custom-state be) {}) :sub-id 0))))

(defn- fan-item-stack? [stack]
  (when (and stack (not (item/item-is-empty? stack)))
    (let [p (-> (try (some-> stack item/item-get-item item/item-get-registry-name) (catch Exception _ nil))
                id-path)]
      (= p "windgen_fan"))))

(defn- pillar-item-stack? [stack]
  (when (and stack (not (item/item-is-empty? stack)))
    (let [p (-> (try (some-> stack item/item-get-item item/item-get-registry-name) (catch Exception _ nil))
                id-path)]
      (wind-pillar-id? p))))

(defn- rotate-offset [direction [x y z]]
  (case (keyword (name (or direction :north)))
    :east [(- z) y x]
    :south [(- x) y (- z)]
    :west [z y (- x)]
    [x y z]))

(defn- no-obstacle? [level p direction]
  (loop [i -7]
    (if (> i 7)
      true
      (if (loop [j -7]
            (cond
              (> j 7) nil
              (and (zero? i) (zero? j)) (recur (inc j))
              :else
              (let [[dx dy dz] (rotate-offset direction [i j -1])
                    check-pos (pos/create-block-pos (+ (pos/pos-x p) dx)
                                                    (+ (pos/pos-y p) dy)
                                                    (+ (pos/pos-z p) dz))
                    st (world/world-get-block-state* level check-pos)]
                (if (world/block-state-is-air? st) (recur (inc j)) j))))
        false
        (recur (inc i))))))

(defn- find-base-below [level p]
  (loop [y (dec (pos/pos-y p)) pillars 0]
    (let [check-pos (pos/create-block-pos (pos/pos-x p) y (pos/pos-z p))
          be (world/world-get-tile-entity* level check-pos)
          bid (when be (platform-be/get-block-id be))]
      (cond
        (wind-pillar-id? bid)
        (if (< pillars wind-config/max-pillars) (recur (dec y) (inc pillars)) nil)

        (wind-base-id? bid)
        {:base-pos check-pos :pillars pillars}

        :else nil))))

(defn- find-main-above-from-base [level base-pos]
  (loop [y (+ (pos/pos-y base-pos) 2) pillars 0]
    (let [check-pos (pos/create-block-pos (pos/pos-x base-pos) y (pos/pos-z base-pos))
          be (world/world-get-tile-entity* level check-pos)
          bid (when be (platform-be/get-block-id be))]
      (cond
        (wind-pillar-id? bid)
        (if (< pillars wind-config/max-pillars)
          (recur (inc y) (inc pillars))
          {:completeness :no-top})

        (wind-main-id? bid)
        (if (and be (sub-id-zero? be) (>= pillars wind-config/min-pillars))
          {:completeness :complete :main-pos check-pos :pillars pillars}
          {:completeness :no-top})

        :else
        {:completeness (if (< pillars wind-config/min-pillars) :base-only :no-top)}))))

(defn- completeness->status [completeness generating?]
  (case completeness
    :complete (if generating? "COMPLETE" "COMPLETE_NOT_WORKING")
    :no-top "NO_TOP"
    "BASE_ONLY"))

(defn- maybe-charge-output-item [state]
  (let [stack (get-in state [:inventory 0])
        cur (double (get state :energy 0.0))]
    (if (and stack (energy/is-energy-item-supported? stack) (pos? cur))
      (let [item-cur (double (energy/get-item-energy stack))
            item-max (double (energy/get-item-max-energy stack))
            need (max 0.0 (- item-max item-cur))
            amount (min cur need)
            leftover (double (energy/charge-energy-to-item stack amount false))
            accepted (max 0.0 (- amount leftover))]
        (if (pos? accepted)
          (assoc state :energy (- cur accepted))
          state))
      state)))

(defn- main-tick-fn [level p _block-state be]
  (when (and level (not (world/world-is-client-side* level)) (sub-id-zero? be))
    (let [state0 (or (platform-be/get-custom-state be) main-default-state)
          ticker (inc (int (get state0 :update-ticker 0)))
          state1 (assoc state0 :update-ticker ticker)]
      (if (zero? (mod ticker wind-config/structure-update-interval))
        (let [fan? (boolean (fan-item-stack? (get-in state1 [:inventory 0])))
              base-info (find-base-below level p)
              complete? (and base-info (>= (:pillars base-info 0) wind-config/min-pillars))
              obstacle-free? (and complete? (no-obstacle? level p (:direction state1 :north)))
              state2 (assoc state1
                       :fan-installed fan?
                       :complete (boolean complete?)
                       :no-obstacle (boolean obstacle-free?)
                       :status (if complete? "COMPLETE" "INCOMPLETE"))]
          (when (not= state2 state0)
            (platform-be/set-custom-state! be state2)
            (platform-be/set-changed! be)))
        (when (not= state1 state0)
          (platform-be/set-custom-state! be state1))))))

(defn- base-tick-fn [level p _block-state be]
  (when (and level (not (world/world-is-client-side* level)) (sub-id-zero? be))
    (let [state0 (or (platform-be/get-custom-state be) base-default-state)
          ticker (inc (int (get state0 :update-ticker 0)))
          state1 (assoc state0 :update-ticker ticker)
          scan-info (when (zero? (mod ticker wind-config/structure-update-interval))
                      (find-main-above-from-base level p))
          state2 (if scan-info
                   (let [comp (:completeness scan-info :base-only)
                         mpos (:main-pos scan-info)]
                     (cond-> (assoc state1 :completeness (name comp))
                       mpos (assoc :main-pos-x (pos/pos-x mpos)
                                   :main-pos-y (pos/pos-y mpos)
                                   :main-pos-z (pos/pos-z mpos))))
                   state1)
          main-pos (when (= (:completeness state2) "complete")
                     (let [mx (:main-pos-x state2)
                           my (:main-pos-y state2)
                           mz (:main-pos-z state2)]
                       (when (and (number? mx) (number? my) (number? mz))
                         (pos/create-block-pos mx my mz))))
          main-be (when main-pos (world/world-get-tile-entity* level main-pos))
          main-state (when main-be (platform-be/get-custom-state main-be))
          working? (and (= (:completeness state2) "complete")
                        (true? (:no-obstacle main-state))
                        (true? (:fan-installed main-state)))
          gen-speed (if working? (wind-config/calculate-generation-rate (:main-pos-y state2)) 0.0)
          energy-before (double (get state2 :energy 0.0))
          max-energy (double (get state2 :max-energy wind-config/max-energy-base))
          energy-after (min max-energy (+ energy-before gen-speed))
          state3 (assoc state2
                   :energy energy-after
                   :gen-speed (double gen-speed)
                   :status (completeness->status (keyword (:completeness state2 "base-only")) working?))
          state4 (maybe-charge-output-item state3)]
      (when (not= state4 state0)
        (platform-be/set-custom-state! be state4)
        (platform-be/set-changed! be)))))

(defn- pillar-tick-fn [level _p _block-state be]
  (when (and level (not (world/world-is-client-side* level)))
    (let [state0 (or (platform-be/get-custom-state be) pillar-default-state)
          state1 (update state0 :update-ticker (fnil inc 0))]
      (when (not= state1 state0)
        (platform-be/set-custom-state! be state1)))))

(defn- open-wind-main-gui! [{:keys [player world pos sneaking item-stack]}]
  ;; Follow Minecraft 1.20 use order:
  ;; when player is holding the pillar block item, let item placement run (PASS).
  (when (and player world pos (not sneaking) (not (pillar-item-stack? item-stack)))
    (if-let [open-gui-by-type (requiring-resolve 'cn.li.ac.wireless.gui.registry/open-gui-by-type)]
      (open-gui-by-type player :wind-gen-main world pos)
      nil)))

(defn- open-wind-base-gui! [{:keys [player world pos sneaking item-stack]}]
  ;; Keep placement path for pillar item: return nil/PASS to let BlockItem#useOn run.
  (when (and player world pos (not sneaking) (not (pillar-item-stack? item-stack)))
    (if-let [open-gui-by-type (requiring-resolve 'cn.li.ac.wireless.gui.registry/open-gui-by-type)]
      (open-gui-by-type player :wind-gen-base world pos)
      nil)))

(defn- main-controller-pos-at
  [level p bid]
  (let [be (world/world-get-tile-entity* level p)
        st (when be (or (platform-be/get-custom-state be) {}))]
    (cond
      ;; Main controller block itself
      (and (contains? wind-main-controller-ids (id-path bid)) be (sub-id-zero? be))
      p

      ;; Main part block: resolve linked controller from part state
      (contains? wind-main-part-ids (id-path bid))
      (let [cx (:controller-pos-x st)
            cy (:controller-pos-y st)
            cz (:controller-pos-z st)]
        (when (and (number? cx) (number? cy) (number? cz))
          (let [cp (pos/create-block-pos cx cy cz)
                cbe (world/world-get-tile-entity* level cp)
                cbid (when cbe (platform-be/get-block-id cbe))]
            (when (and (contains? wind-main-controller-ids (id-path cbid)) (sub-id-zero? cbe))
              cp))))

      :else
      nil)))

(defn- base-controller-pos-at
  [level p bid]
  (let [be (world/world-get-tile-entity* level p)
        st (when be (or (platform-be/get-custom-state be) {}))]
    (cond
      ;; Base controller block itself
      (and (contains? wind-base-controller-ids (id-path bid)) be (sub-id-zero? be))
      p

      ;; Base part block: resolve linked controller from part state
      (contains? wind-base-part-ids (id-path bid))
      (let [cx (:controller-pos-x st)
            cy (:controller-pos-y st)
            cz (:controller-pos-z st)]
        (when (and (number? cx) (number? cy) (number? cz))
          (let [cp (pos/create-block-pos cx cy cz)
                cbe (world/world-get-tile-entity* level cp)
                cbid (when cbe (platform-be/get-block-id cbe))]
            (when (and (contains? wind-base-controller-ids (id-path cbid)) (sub-id-zero? cbe))
              cp))))

      :else
      nil)))

(defn- pillar-column-aligned-with-main?
  [pillar-pos main-controller-pos]
  (and (= (pos/pos-x pillar-pos) (pos/pos-x main-controller-pos))
       (= (pos/pos-z pillar-pos) (pos/pos-z main-controller-pos))))

(defn- pillar-column-aligned-with-support?
  [pillar-pos support-pos]
  (and (= (pos/pos-x pillar-pos) (pos/pos-x support-pos))
       (= (pos/pos-z pillar-pos) (pos/pos-z support-pos))))

(defn- valid-pillar-support?
  [level p]
  ;; Rule:
  ;; - Pillar can be stacked on pillar.
  ;; - The bottom of that pillar chain must resolve to a valid wind-gen-base/main controller.
  ;; - Pillar column x/z must match that resolved controller's x/z.
  ;; - Total chain height is capped by wind-config/max-pillars.
  (loop [y (dec (pos/pos-y p))
         pillars 0]
    (let [check-pos (pos/create-block-pos (pos/pos-x p) y (pos/pos-z p))
          be (world/world-get-tile-entity* level check-pos)
          bid (when be (platform-be/get-block-id be))]
      (cond
        (wind-pillar-id? bid)
        (if (< pillars wind-config/max-pillars)
          (recur (dec y) (inc pillars))
          false)

        (wind-main-id? bid)
        (if-let [controller-pos (main-controller-pos-at level check-pos bid)]
          (pillar-column-aligned-with-main? p controller-pos)
          false)

        (wind-base-id? bid)
        (if-let [controller-pos (base-controller-pos-at level check-pos bid)]
          (pillar-column-aligned-with-main? p controller-pos)
          ;; Fallback for freshly-placed base-part where controller linkage may not
          ;; be persisted yet at this tick; still enforce same x/z column.
          (pillar-column-aligned-with-support? p check-pos))

        :else
        false))))

(defn- on-wind-pillar-placed!
  [{:keys [world pos] :as _ctx}]
  (when (and world pos (not (world/world-is-client-side* world)))
    (when-not (valid-pillar-support? world pos)
      (let [below-pos (pos/create-block-pos (pos/pos-x pos) (dec (pos/pos-y pos)) (pos/pos-z pos))
            below-be (world/world-get-tile-entity* world below-pos)
            below-id (when below-be (platform-be/get-block-id below-be))]
        (log/info "wind-gen pillar place rejected:"
                  {:pos [(pos/pos-x pos) (pos/pos-y pos) (pos/pos-z pos)]
                   :below-pos [(pos/pos-x below-pos) (pos/pos-y below-pos) (pos/pos-z below-pos)]
                   :below-id below-id
                   :below-id-path (id-path below-id)}))
      {:cancel-place? true
       :messages [{:type :literal
                   :text "wind_gen_pillar must be in the same x/z column above wind_gen_base or wind_gen_main controller."}]})))

(defn- handle-get-status-main [payload player]
  (let [w (net-helpers/get-world player)
        tile (net-helpers/get-tile-at w payload)
        state (or (and tile (platform-be/get-custom-state tile)) main-default-state)]
    {:complete (boolean (:complete state false))
     :no-obstacle (boolean (:no-obstacle state false))
     :fan-installed (boolean (:fan-installed state false))
     :status (str (:status state "INCOMPLETE"))}))

(defn- handle-get-status-base [payload player]
  (let [w (net-helpers/get-world player)
        tile (net-helpers/get-tile-at w payload)
        state (or (and tile (platform-be/get-custom-state tile)) base-default-state)]
    {:energy (double (:energy state 0.0))
     :max-energy (double (:max-energy state wind-config/max-energy-base))
     :gen-speed (double (:gen-speed state 0.0))
     :status (str (:status state "BASE_ONLY"))
     :completeness (str (:completeness state "BASE_ONLY"))}))

(defn register-network-handlers! []
  (net-server/register-handler (msg :get-status-main) handle-get-status-main)
  (net-server/register-handler (msg :get-status-base) handle-get-status-base)
  (log/info "Wind Generator network handlers registered"))

(defonce ^:private wind-gen-installed? (atom false))

(defn init-wind-gen! []
  (when (compare-and-set! wind-gen-installed? false true)
    (msg-registry/register-block-messages! :wind-gen [:get-status-main :get-status-base])

    (tdsl/register-tile!
      (tdsl/create-tile-spec
        "wind-gen-main"
        {:registry-name "wind_gen_main"
         :impl :scripted
         :blocks ["wind-gen-main" "wind-gen-main-part"]
         :tick-fn main-tick-fn
         :read-nbt-fn main-scripted-load-fn
         :write-nbt-fn main-scripted-save-fn}))

    (tdsl/register-tile!
      (tdsl/create-tile-spec
        "wind-gen-base"
        {:registry-name "wind_gen_base"
         :impl :scripted
         :blocks ["wind-gen-base" "wind-gen-base-part"]
         :tick-fn base-tick-fn
         :read-nbt-fn base-scripted-load-fn
         :write-nbt-fn base-scripted-save-fn}))

    (tdsl/register-tile!
      (tdsl/create-tile-spec
        "wind-gen-pillar"
        {:registry-name "wind_gen_pillar"
         :impl :scripted
         :blocks ["wind-gen-pillar"]
         :tick-fn pillar-tick-fn
         :read-nbt-fn pillar-scripted-load-fn
         :write-nbt-fn pillar-scripted-save-fn}))

    (platform-cap/declare-capability! :wind-generator IWirelessGenerator
      (fn [be _side] (impls/->WirelessGeneratorImpl be)))
    (tile-logic/register-tile-capability! "wind-gen-base" :wind-generator)

    (bdsl/defmultiblock 'wind-gen-main
      :multi-block {:positions [[0 0 0] [0 0 -1] [0 0 1]]
                    :rotation-center [0.5 0.0 0.4]
                    :tesr-use-raw-rotation-center? true}
      :common {:physical {:material :metal
                          :hardness 3.0
                          :resistance 6.0
                          :requires-tool true
                          :harvest-tool :pickaxe
                          :harvest-level 1
                          :sounds :metal}}
      :controller {:registry-name "wind_gen_main"
                   :rendering {:flat-item-icon? true
                               :textures {:all (modid/asset-path "block" "wind_gen_main")}}
                   :events {:on-right-click open-wind-main-gui!}}
      :part {:registry-name "wind_gen_main_part"
             :rendering {:model-parent "minecraft:block/cube_all"
                         :textures {:all (modid/asset-path "block" "wind_gen_main")}}
             :events {:on-right-click open-wind-main-gui!}})

    (bdsl/defmultiblock 'wind-gen-base
      :multi-block {:positions [[0 0 0] [0 1 0]]
                    :rotation-center [0.5 0.0 0.5]
                    :tesr-use-raw-rotation-center? true}
      :common {:physical {:material :metal
                          :hardness 3.0
                          :resistance 6.0
                          :requires-tool true
                          :harvest-tool :pickaxe
                          :harvest-level 1
                          :sounds :metal}}
      :controller {:registry-name "wind_gen_base"
                   :rendering {:flat-item-icon? true
                               :textures {:all (modid/asset-path "block" "wind_gen_base")}}
                   :events {:on-right-click open-wind-base-gui!}}
      :part {:registry-name "wind_gen_base_part"
             :rendering {:model-parent "minecraft:block/cube_all"
                         :textures {:all (modid/asset-path "block" "wind_gen_base")}}
             :events {:on-right-click open-wind-base-gui!}})

    (bdsl/register-block!
      (bdsl/create-block-spec
        "wind-gen-pillar"
        {:registry-name "wind_gen_pillar"
         :physical {:material :metal
                    :hardness 3.0
                    :resistance 6.0
                    :requires-tool true
                    :harvest-tool :pickaxe
                    :harvest-level 1
                    :sounds :metal}
         :events {:on-place on-wind-pillar-placed!}
         :rendering {:model-parent "minecraft:block/cube_all"
                     :textures {:all (modid/asset-path "block" "wind_gen_pillar")}
                     :flat-item-icon? true}}))

    (hooks/register-network-handler! register-network-handlers!)
    (hooks/register-client-renderer! 'cn.li.ac.block.wind-gen.render/init!)
    (log/info "Initialized Wind Generator blocks (main/base multiblock + pillar)")))