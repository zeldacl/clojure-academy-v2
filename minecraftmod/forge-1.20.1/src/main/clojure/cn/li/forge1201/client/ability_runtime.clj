(ns cn.li.forge1201.client.ability-runtime
  "CLIENT-ONLY ability input runtime (minimal runnable loop)."
  (:require [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.skill :as skill]
            [cn.li.mcmod.ability.catalog :as catalog]
            [cn.li.forge1201.ability.network :as ability-net]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.event TickEvent$ClientTickEvent TickEvent$Phase]
           [net.minecraftforge.eventbus.api EventPriority]))

(defonce ^:private local-contexts (atom {}))
(defonce ^:private sync-cache (atom {}))
(defonce ^:private slot-active-contexts (atom {}))
(defonce ^:private client-tick-counter (atom 0))
(defonce ^:private tick-listener-registered? (atom false))

(def ^:private keepalive-interval-ticks 10)

(defn active-contexts [] @local-contexts)

(defn latest-sync
  [player-uuid]
  (get @sync-cache player-uuid))

(defn- player-runtime-state
  [player-uuid]
  (or (ps/get-player-state player-uuid)
      (latest-sync player-uuid)))

(defn- resolve-slot-skill-id
  [player-uuid key-idx]
  (when-let [state (player-runtime-state player-uuid)]
    (let [preset (:preset-data state)
          active (or (:active-preset preset) 0)
          binding (get-in preset [:slots [active key-idx]])]
      (when (and (vector? binding) (= 2 (count binding)))
        (skill/get-skill-by-controllable (first binding) (second binding))))))

(defn- slot-key
  [player-uuid key-idx]
  [player-uuid (int key-idx)])

(defn- abort-slot-contexts-for-player!
  [player-uuid]
  (doseq [[[uuid _key] ctx-id] @slot-active-contexts]
    (when (= uuid player-uuid)
      (when (get @local-contexts ctx-id)
        (ability-net/send-to-server! catalog/MSG-SKILL-KEY-ABORT {:ctx-id ctx-id})
        (ctx/terminate-context! ctx-id nil)
        (swap! local-contexts dissoc ctx-id))
      (swap! slot-active-contexts dissoc [uuid _key]))))

(defn- refresh-local-context! [ctx-id]
  (if-let [c (ctx/get-context ctx-id)]
    (swap! local-contexts assoc ctx-id c)
    (swap! local-contexts dissoc ctx-id)))

(defn- send-keepalive! [ctx-id]
  (ability-net/send-to-server! catalog/MSG-CTX-KEEPALIVE {:ctx-id ctx-id}))

(defn tick-client!
  []
  (let [tick (swap! client-tick-counter inc)]
    ;; Poll key inputs (delegates to ability-input)
    (when-let [tick-input-fn (resolve 'cn.li.forge1201.client.ability-input/tick-input!)]
      (@tick-input-fn))

    ;; Poll particle effects
    (when-let [tick-particles-fn (resolve 'cn.li.forge1201.client.effects.particle-bridge/tick-particles!)]
      (@tick-particles-fn))

    ;; Poll sound effects
    (when-let [tick-sounds-fn (resolve 'cn.li.forge1201.client.effects.sound-bridge/tick-sounds!)]
      (@tick-sounds-fn))

    ;; Send keepalive for active contexts
    (when (zero? (mod tick keepalive-interval-ticks))
      (doseq [ctx-id (keys @local-contexts)]
        (send-keepalive! ctx-id)))))

(defn- on-client-tick [^TickEvent$ClientTickEvent evt]
  (when (= TickEvent$Phase/END (.phase evt))
    (tick-client!)))

(defn- on-ctx-establish!
  [{:keys [ctx-id server-id]}]
  (ctx/transition-to-alive! ctx-id server-id nil)
  (refresh-local-context! ctx-id))

(defn- on-ctx-terminate!
  [{:keys [ctx-id]}]
  (ctx/terminate-context! ctx-id nil)
  (swap! local-contexts dissoc ctx-id))

(defn- on-ctx-channel!
  [{:keys [ctx-id channel payload]}]
  (ctx/ctx-send-to-local! ctx-id channel payload))

(defn- on-sync-ability!
  [{:keys [uuid ability-data]}]
  (when uuid
    (ps/get-or-create-player-state! uuid)
    (ps/update-ability-data! uuid (constantly ability-data))
    (swap! sync-cache update uuid (fnil assoc {}) :ability-data ability-data)))

(defn- on-sync-resource!
  [{:keys [uuid resource-data]}]
  (when uuid
    (ps/get-or-create-player-state! uuid)
    (ps/update-resource-data! uuid (constantly resource-data))
    (swap! sync-cache update uuid (fnil assoc {}) :resource-data resource-data)))

(defn- on-sync-cooldown!
  [{:keys [uuid cooldown-data]}]
  (when uuid
    (ps/get-or-create-player-state! uuid)
    (ps/update-cooldown-data! uuid (constantly cooldown-data))
    (swap! sync-cache update uuid (fnil assoc {}) :cooldown-data cooldown-data)))

(defn- on-sync-preset!
  [{:keys [uuid preset-data]}]
  (when uuid
    (let [old-active (get-in (latest-sync uuid) [:preset-data :active-preset])
          new-active (:active-preset preset-data)]
      (when (and (some? old-active)
                 (some? new-active)
                 (not= old-active new-active))
        (abort-slot-contexts-for-player! uuid)))
    (ps/get-or-create-player-state! uuid)
    (ps/update-preset-data! uuid (constantly preset-data))
    (swap! sync-cache update uuid (fnil assoc {}) :preset-data preset-data)))

(defn register-push-handlers!
  []
  (net-client/register-push-handler! catalog/MSG-CTX-ESTABLISH on-ctx-establish!)
  (net-client/register-push-handler! catalog/MSG-CTX-TERMINATE on-ctx-terminate!)
  (net-client/register-push-handler! catalog/MSG-CTX-CHANNEL on-ctx-channel!)
  (net-client/register-push-handler! catalog/MSG-SYNC-ABILITY on-sync-ability!)
  (net-client/register-push-handler! catalog/MSG-SYNC-RESOURCE on-sync-resource!)
  (net-client/register-push-handler! catalog/MSG-SYNC-COOLDOWN on-sync-cooldown!)
  (net-client/register-push-handler! catalog/MSG-SYNC-PRESET on-sync-preset!))

(defn on-key-down!
  [player-uuid skill-id]
  (let [c (ctx/new-context player-uuid skill-id)]
    (ctx/register-context! c)
    (swap! local-contexts assoc (:id c) c)
    (ability-net/send-to-server! catalog/MSG-CTX-BEGIN-LINK
                                 {:ctx-id (:id c) :skill-id skill-id})
    (ability-net/send-to-server! catalog/MSG-SKILL-KEY-DOWN
                                 {:ctx-id (:id c) :skill-id skill-id})
    c))

(defn on-key-tick!
  [ctx-id]
  (when-let [c (get @local-contexts ctx-id)]
    (ability-net/send-to-server! catalog/MSG-SKILL-KEY-TICK
                                 {:ctx-id ctx-id :skill-id (:skill-id c)})))

(defn on-key-up!
  [ctx-id]
  (when (get @local-contexts ctx-id)
    (ability-net/send-to-server! catalog/MSG-SKILL-KEY-UP {:ctx-id ctx-id})
    (ctx/terminate-context! ctx-id nil)
    (swap! local-contexts dissoc ctx-id)))

(defn on-slot-key-down!
  [player-uuid key-idx]
  (let [k (slot-key player-uuid key-idx)]
    (when-not (get @slot-active-contexts k)
      (when-let [skill-id (resolve-slot-skill-id player-uuid key-idx)]
        (let [c (on-key-down! player-uuid skill-id)]
          (swap! slot-active-contexts assoc k (:id c))
          c)))))

(defn on-slot-key-tick!
  [player-uuid key-idx]
  (when-let [ctx-id (get @slot-active-contexts (slot-key player-uuid key-idx))]
    (on-key-tick! ctx-id)))

(defn on-slot-key-up!
  [player-uuid key-idx]
  (let [k (slot-key player-uuid key-idx)]
    (when-let [ctx-id (get @slot-active-contexts k)]
      (on-key-up! ctx-id)
      (swap! slot-active-contexts dissoc k))))

(defn abort-all!
  []
  (doseq [ctx-id (keys @local-contexts)]
    (ability-net/send-to-server! catalog/MSG-SKILL-KEY-ABORT {:ctx-id ctx-id})
    (ctx/terminate-context! ctx-id nil))
  (reset! slot-active-contexts {})
  (reset! local-contexts {}))

(defn init! []
  (register-push-handlers!)
  (when (compare-and-set! tick-listener-registered? false true)
    (.addListener (MinecraftForge/EVENT_BUS)
                  EventPriority/NORMAL false TickEvent$ClientTickEvent
                  (reify java.util.function.Consumer
                    (accept [_ evt] (on-client-tick evt)))))
  (log/info "Ability client runtime initialized"))
