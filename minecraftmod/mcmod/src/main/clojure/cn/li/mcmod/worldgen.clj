(ns cn.li.mcmod.worldgen
  "Platform-neutral worldgen registries.

  Content modules register descriptors during init. Platform worldgen
  providers read these registries at datagen/feature-init time instead
  of hardcoding content-specific block/feature IDs.")

;; --- pool fill block ---

(defonce ^:private pool-fill-block-id* (atom nil))

(defn register-pool-fill-block-id!
  "Register the block ID (e.g. \"modid:block_name\") used as the fill block
  in configurable pool features. Called by content during block registration."
  [mod-colon-path]
  (reset! pool-fill-block-id* (str mod-colon-path))
  nil)

(defn get-pool-fill-block-id
  "Return the registered pool fill block ID string, or nil if not yet set."
  []
  @pool-fill-block-id*)

(defn reset-pool-fill-block-id-for-test!
  []
  (reset! pool-fill-block-id* nil)
  nil)

;; --- worldgen ore descriptors ---

(defonce ^:private worldgen-ore-registry* (atom []))

(defn register-worldgen-ore!
  "Register an ore generation descriptor.
  descriptor: {:id key :name str :size int :count int :enabled? bool ...}"
  [descriptor]
  (swap! worldgen-ore-registry* conj descriptor)
  nil)

(defn list-worldgen-ores
  []
  @worldgen-ore-registry*)

(defn reset-worldgen-ores-for-test!
  []
  (reset! worldgen-ore-registry* [])
  nil)

;; --- worldgen liquid descriptors ---

(defonce ^:private worldgen-liquid-registry* (atom []))

(defn register-worldgen-liquid!
  "Register a liquid generation descriptor.
  descriptor: {:id key :block-id str :name str :count int :enabled? bool ...}"
  [descriptor]
  (swap! worldgen-liquid-registry* conj descriptor)
  nil)

(defn list-worldgen-liquids
  []
  @worldgen-liquid-registry*)

(defn reset-worldgen-liquids-for-test!
  []
  (reset! worldgen-liquid-registry* [])
  nil)
