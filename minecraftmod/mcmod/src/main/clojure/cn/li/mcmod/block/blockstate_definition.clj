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

;; ============================================================================
;; Definitions generation (datagen)
;; ============================================================================

(defn get-all-definitions
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

(defn get-block-state-definition
  [block-key]
  (or (some-> (get-all-definitions) (get block-key))
      (some-> (get-all-definitions) (get (keyword (name block-key))))))

(defn is-multipart-block?
  "Multipart when parts count > 1."
  [definition]
  (> (count (:parts definition)) 1))

;; End of file
