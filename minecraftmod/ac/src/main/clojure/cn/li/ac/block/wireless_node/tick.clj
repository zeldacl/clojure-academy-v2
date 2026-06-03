(ns cn.li.ac.block.wireless-node.tick
  "Wireless node server tick behavior: energy IO, network status, and GUI sync."
  (:require [cn.li.ac.block.machine.runtime :as machine-runtime]
            [cn.li.ac.block.wireless-node.inventory :as node-inventory]
            [cn.li.ac.block.wireless-node.state :as node-state]
            [cn.li.ac.block.machine.sync :as machine-sync]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.ac.wireless.config :as node-config]
            [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.wireless.api :as wireless-api]
            [cn.li.ac.wireless.data.network-state :as network-state]
            [cn.li.mcmod.block.state-schema :as state-schema]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.position :as pos]))

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

(defn- sync-blockstate-if-changed!
  [_be level pos old-state new-state _ctx]
  (when (and level pos (zero? (mod (get new-state :update-ticker 0) (node-config/sync-interval))))
    (let [old-sync (::last-broadcast-state old-state)
          old-level (node-state/energy->blockstate-level (:energy old-sync 0) new-state)
          new-level (node-state/energy->blockstate-level (:energy new-state 0) new-state)
          old-enabled (:enabled old-sync false)
          new-enabled (:enabled new-state false)]
      (when (or (not= new-level old-level) (not= new-enabled old-enabled))
        (node-state/update-block-state! new-state level pos)))))

(defn node-tick-state
  [state {:keys [level pos be]}]
  (let [ticker (inc (long (get state :update-ticker 0)))
        state1 (assoc state :update-ticker ticker)
        state2 (try (tick-charge-in state1) (catch Exception _ state1))
        state3 (try (tick-charge-out state2) (catch Exception _ state2))]
    (if (zero? (mod ticker (node-config/sync-interval)))
      (let [state4 (try (tick-check-network state3 level pos be) (catch Exception _ state3))
            old-sync (::last-broadcast-state state4)
            new-sync (machine-sync/broadcast-if-changed!
                       level pos node-state/node-state-schema state4 old-sync "node"
                       :be be
                       :extra-payload (fn [payload _ _ _]
                                        (assoc payload :max-energy (node-state/node-max-energy state4))))]
        (cond-> state4 new-sync (assoc ::last-broadcast-state new-sync)))
      state3)))

(def node-scripted-tick-fn
  (machine-runtime/make-tick-fn
    {:default-state node-state/node-default-state
     :initial-state (fn [be _ctx]
                      (node-state/node-safe-state be (platform-be/get-block-id be)))
     :tick-state node-tick-state
     :after-commit! sync-blockstate-if-changed!}))
