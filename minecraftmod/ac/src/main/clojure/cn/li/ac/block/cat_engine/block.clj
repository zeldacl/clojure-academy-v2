(ns cn.li.ac.block.cat-engine.block
  "Cat Engine block - automatic wireless linking for generators.

  This block automatically searches for nearby wireless nodes and links
  generators to them. It has no GUI and operates transparently.

  Architecture:
  All persistent state lives in ScriptedBlockEntity.customState as Clojure maps."
  (:require [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.block.tile-dsl :as tdsl]
            [cn.li.mcmod.block.state-schema :as state-schema]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.ac.block.cat-engine.config :as cat-config]
            [cn.li.ac.block.cat-engine.schema :as cat-schema]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.config.modid :as modid]))

;; ============================================================================
;; Schema Generation
;; ============================================================================

(def cat-state-schema
  (state-schema/filter-server-fields cat-schema/cat-engine-schema))

(def cat-default-state
  (state-schema/schema->default-state cat-state-schema))

(def cat-scripted-load-fn
  (state-schema/schema->load-fn cat-state-schema))

(def cat-scripted-save-fn
  (state-schema/schema->save-fn cat-state-schema))

;; ============================================================================
;; Node Search and Linking
;; ============================================================================

(defn- find-nearby-nodes
  "Find wireless nodes within search radius"
  [level pos]
  ;; TODO: Implement node search using wireless API
  ;; This will need to use the wireless system to find nearby nodes
  ;; For now, return empty list
  [])

(defn- attempt-link-to-node
  "Attempt to link to a wireless node"
  [level engine-pos node-pos]
  ;; TODO: Implement linking logic using wireless API
  ;; This will need to create a link between the engine and the node
  ;; For now, return false (link failed)
  false)

;; ============================================================================
;; Tick Logic
;; ============================================================================

(defn- cat-tick-fn
  "Tick handler for cat engine"
  [level pos _block-state be]
  (when (and level (not (world/world-is-client-side level)))
    (let [state (or (platform-be/get-custom-state be) cat-default-state)
          ticker (inc (get state :update-ticker 0))
          state (assoc state :update-ticker ticker)

          ;; Decrease cooldown
          cooldown (max 0 (dec (get state :search-cooldown 0)))
          state (assoc state :search-cooldown cooldown)

          ;; Search for nodes if not on cooldown and auto-link is enabled
          state (if (and cat-config/auto-link-enabled?
                         (zero? cooldown)
                         (zero? (mod ticker cat-config/search-interval))
                         (not (:has-link state false)))
                  (let [nodes (find-nearby-nodes level pos)]
                    (if (seq nodes)
                      (let [target-node (if cat-config/prefer-closest?
                                          (first nodes)
                                          (rand-nth nodes))
                            link-success? (attempt-link-to-node level pos target-node)]
                        (if link-success?
                          (assoc state
                                 :has-link true
                                 :linked-node-x (pos/pos-x target-node)
                                 :linked-node-y (pos/pos-y target-node)
                                 :linked-node-z (pos/pos-z target-node)
                                 :link-attempts 0)
                          (let [attempts (inc (get state :link-attempts 0))]
                            (if (>= attempts cat-config/max-link-attempts)
                              (assoc state
                                     :link-attempts 0
                                     :search-cooldown cat-config/link-cooldown)
                              (assoc state :link-attempts attempts)))))
                      state))
                  state)]

      (when (not= state (platform-be/get-custom-state be))
        (platform-be/set-custom-state! be state)
        (platform-be/set-changed! be)))))

;; ============================================================================
;; Tile Registration
;; ============================================================================

(tdsl/deftile cat-engine-tile
  :id "cat-engine"
  :registry-name "cat_engine"
  :impl :scripted
  :blocks ["cat-engine"]
  :tick-fn cat-tick-fn
  :read-nbt-fn cat-scripted-load-fn
  :write-nbt-fn cat-scripted-save-fn)

;; ============================================================================
;; Block Definition
;; ============================================================================

(bdsl/defblock cat-engine
  :registry-name "cat_engine"
  :physical {:material :metal
             :hardness 2.0
             :resistance 6.0
             :requires-tool true
             :harvest-tool :pickaxe
             :harvest-level 1
             :sounds :metal}
  :rendering {:model-parent "minecraft:block/cube_all"
              :textures {:all (modid/asset-path "block" "cat_engine")}
              :flat-item-icon? true
              :light-level 0})

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init-cat-engine!
  []
  (log/info "Initialized Cat Engine block"))
