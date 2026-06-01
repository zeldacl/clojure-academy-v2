(ns cn.li.ac.block.wireless-node.tick
  "Wireless node server tick behavior: energy IO, network status, and GUI sync."
  (:require [cn.li.ac.block.wireless-node.inventory :as node-inventory]
            [cn.li.ac.block.wireless-node.state :as node-state]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.ac.wireless.config :as node-config]
            [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.wireless.api :as wireless-api]
            [cn.li.ac.wireless.data.network-state :as network-state]
            [cn.li.mcmod.block.state-schema :as state-schema]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.position :as pos]))

(def ^:private node-broadcast-fn-lock
  (Object.))

(def ^:private ^:dynamic *broadcast-node-state-fn*
  nil)

(defn- broadcast-node-state-fn
  []
  (or (var-get #'*broadcast-node-state-fn*)
      (locking node-broadcast-fn-lock
        (or (var-get #'*broadcast-node-state-fn*)
            (let [f (requiring-resolve 'cn.li.ac.block.wireless-node.gui/broadcast-node-state)]
              (alter-var-root #'*broadcast-node-state-fn* (constantly f))
              f)))))

(defn tick-charge-in
  "Pull energy from inventory slot 0 into the node. Returns updated state."
  [state]
  (let [input-item (get-in state [:inventory (node-inventory/node-input-slot-index)])]
    (if (and input-item (energy/is-energy-item-supported? input-item))
      (let [cur       (double (:energy state 0.0))
            max-e     (double (node-state/node-max-energy state))
            bandwidth (double (node-config/bandwidth (node-state/node-tier state)))
            needed    (min bandwidth (- max-e cur))
            pulled    (double (energy/pull-energy-from-item input-item needed false))]
        (if (pos? pulled)
          (assoc state :energy (+ cur pulled) :charging-in true)
          (assoc state :charging-in false)))
      (assoc state :charging-in false))))

(defn tick-charge-out
  "Push energy from node to inventory slot 1. Returns updated state."
  [state]
  (let [output-item (get-in state [:inventory (node-inventory/node-output-slot-index)])
        cur (double (:energy state 0.0))]
    (if (and output-item (energy/is-energy-item-supported? output-item) (pos? cur))
      (let [bandwidth (double (node-config/bandwidth (node-state/node-tier state)))
            to-charge (min bandwidth cur)
            leftover  (double (energy/charge-energy-to-item output-item to-charge false))
            charged   (- to-charge leftover)]
        (if (pos? charged)
          (assoc state :energy (- cur charged) :charging-out true)
          (assoc state :charging-out false)))
      (assoc state :charging-out false))))

(defn tick-check-network
  "Update :enabled flag based on wireless network lookup. Returns updated state."
  [state level block-pos node-tile]
  (try
    (let [vblock (vb/create-vnode (pos/pos-x block-pos) (pos/pos-y block-pos) (pos/pos-z block-pos))
          _ (wireless-api/register-node-spatial! level vblock)
          network (wireless-api/get-wireless-net-by-node node-tile)
          connected? (network-state/active? network)]
      (assoc state :enabled connected?))
    (catch Exception _
      (assoc state :enabled false))))

(defn node-scripted-tick-fn
  [level block-pos _block-state be]
  (let [block-id (platform-be/get-block-id be)
        state    (node-state/node-safe-state be block-id)
        ticker   (inc (get state :update-ticker 0))
        state    (assoc state :update-ticker ticker)
        state    (try (tick-charge-in state)  (catch Exception _ state))
        state    (try (tick-charge-out state) (catch Exception _ state))
        state    (if (zero? (mod ticker (node-config/sync-interval)))
                   (let [state (try (tick-check-network state level block-pos be) (catch Exception _ state))
                         old-sync-state (::last-broadcast-state state)
                         new-sync-state (-> (state-schema/schema->sync-payload node-state/node-state-schema state block-pos)
                                            (assoc :max-energy (node-state/node-max-energy state)))
                         old-level (node-state/energy->blockstate-level (:energy old-sync-state 0) state)
                         new-level (node-state/energy->blockstate-level (:energy state 0) state)
                         old-enabled (:enabled old-sync-state false)
                         new-enabled (:enabled state false)]
                     (when (or (not= new-level old-level) (not= new-enabled old-enabled))
                       (node-state/update-block-state! state level block-pos))
                     (when (not= new-sync-state old-sync-state)
                       (try
                         (when-let [broadcast-fn (broadcast-node-state-fn)]
                           (broadcast-fn level block-pos new-sync-state))
                         (catch Exception _)))
                     (assoc state ::last-broadcast-state new-sync-state))
                   state)
        old-state (platform-be/get-custom-state be)]
    (when (not= state old-state)
      (platform-be/set-custom-state! be state)
      (platform-be/set-changed! be))))
