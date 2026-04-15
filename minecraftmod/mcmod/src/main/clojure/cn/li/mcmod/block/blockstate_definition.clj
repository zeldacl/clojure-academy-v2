(ns cn.li.mcmod.block.blockstate-definition
  "BlockState datagen business logic (platform independent).

   This module provides basic blockstate definitions from metadata.
   The ac layer overrides these with specialized multipart definitions.

   It derives all needed information from `cn.li.mcmod.registry.metadata`,
   which is populated by the DSL namespaces at runtime."
  (:require [cn.li.mcmod.registry.metadata :as registry-metadata]
            [cn.li.mcmod.config :as config]))

;; ============================================================================
;; Records
;; ============================================================================

(defrecord BlockStatePart
  [condition
   models])

(defrecord BlockStateDefinition
  [registry-name
   properties
   parts])

(declare get-all-definitions)

;; ============================================================================
;; Definitions generation (datagen)
;; ============================================================================

(defn- basic-get-all-definitions
  "Return map: block-id(keyword) -> BlockStateDefinition.
   Generates simple single-model definitions for all blocks from metadata.
   The ac layer will override these with specialized multipart definitions."
  []
  (into {}
        (for [block-id (registry-metadata/get-all-block-ids)
              :let [registry-name (registry-metadata/get-block-registry-name block-id)]]
          [(keyword block-id)
           (BlockStateDefinition.
            registry-name
            {}
            [(->BlockStatePart nil
                               [(str config/*mod-id* ":block/" registry-name)])])])))

(defn- basic-get-block-state-definition
  [block-key]
  (or (some-> (basic-get-all-definitions) (get block-key))
      (some-> (basic-get-all-definitions) (get (keyword (name block-key))))))

(defn- basic-is-multipart-block?
  [definition]
  (> (count (:parts definition)) 1))

(defn- basic-get-item-model-id
  [mod-id registry-name]
  (str mod-id ":block/" registry-name))

(defonce ^:private blockstate-hooks
  (atom {:get-all-definitions basic-get-all-definitions
         :get-block-state-definition basic-get-block-state-definition
         :is-multipart-block? basic-is-multipart-block?
         :get-model-cube-texture-config (fn [_model-name] nil)
         :get-model-texture-config (fn [_model-name] nil)
         :get-item-model-id basic-get-item-model-id}))

(defn register-blockstate-hooks!
  [hooks]
  (swap! blockstate-hooks merge hooks)
  nil)

(defn get-all-definitions
  []
  ((:get-all-definitions @blockstate-hooks)))

(defn get-block-state-definition
  [block-key]
  ((:get-block-state-definition @blockstate-hooks) block-key))

(defn is-multipart-block?
  "Multipart when parts count > 1."
  [definition]
  ((:is-multipart-block? @blockstate-hooks) definition))

(defn get-model-cube-texture-config
  [model-name]
  ((:get-model-cube-texture-config @blockstate-hooks) model-name))

(defn get-model-texture-config
  [model-name]
  ((:get-model-texture-config @blockstate-hooks) model-name))

(defn get-item-model-id
  [mod-id registry-name]
  ((:get-item-model-id @blockstate-hooks) mod-id registry-name))

;; End of file
