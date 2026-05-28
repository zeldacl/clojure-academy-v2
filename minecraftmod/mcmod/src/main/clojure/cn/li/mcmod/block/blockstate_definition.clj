(ns cn.li.mcmod.block.blockstate-definition
  "BlockState datagen business logic (platform independent).

   This module provides basic blockstate definitions from metadata.
   The ac layer overrides these with specialized multipart definitions.

   It derives all needed information from `cn.li.mcmod.protocol.metadata`,
   which is populated by the DSL namespaces at runtime."
  (:require [cn.li.mcmod.protocol.metadata :as registry-metadata]
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

(def ^:private ^:dynamic *get-all-definitions-hook*
  basic-get-all-definitions)

(def ^:private ^:dynamic *get-block-state-definition-hook*
  basic-get-block-state-definition)

(def ^:private ^:dynamic *is-multipart-block-hook*
  basic-is-multipart-block?)

(def ^:private ^:dynamic *get-model-cube-texture-config-hook*
  (fn [_model-name] nil))

(def ^:private ^:dynamic *get-model-texture-config-hook*
  (fn [_model-name] nil))

(def ^:private ^:dynamic *get-item-model-id-hook*
  basic-get-item-model-id)

(defn blockstate-hooks-snapshot
  []
  {:get-all-definitions *get-all-definitions-hook*
   :get-block-state-definition *get-block-state-definition-hook*
   :is-multipart-block? *is-multipart-block-hook*
   :get-model-cube-texture-config *get-model-cube-texture-config-hook*
   :get-model-texture-config *get-model-texture-config-hook*
   :get-item-model-id *get-item-model-id-hook*})

(defn restore-blockstate-hooks!
  [{:keys [get-all-definitions
           get-block-state-definition
           is-multipart-block?
           get-model-cube-texture-config
           get-model-texture-config
           get-item-model-id]}]
  (alter-var-root #'*get-all-definitions-hook* (constantly get-all-definitions))
  (alter-var-root #'*get-block-state-definition-hook* (constantly get-block-state-definition))
  (alter-var-root #'*is-multipart-block-hook* (constantly is-multipart-block?))
  (alter-var-root #'*get-model-cube-texture-config-hook* (constantly get-model-cube-texture-config))
  (alter-var-root #'*get-model-texture-config-hook* (constantly get-model-texture-config))
  (alter-var-root #'*get-item-model-id-hook* (constantly get-item-model-id))
  nil)

(defn register-blockstate-hooks!
  [hooks]
  (when-let [f (:get-all-definitions hooks)]
    (alter-var-root #'*get-all-definitions-hook* (constantly f)))
  (when-let [f (:get-block-state-definition hooks)]
    (alter-var-root #'*get-block-state-definition-hook* (constantly f)))
  (when-let [f (:is-multipart-block? hooks)]
    (alter-var-root #'*is-multipart-block-hook* (constantly f)))
  (when-let [f (:get-model-cube-texture-config hooks)]
    (alter-var-root #'*get-model-cube-texture-config-hook* (constantly f)))
  (when-let [f (:get-model-texture-config hooks)]
    (alter-var-root #'*get-model-texture-config-hook* (constantly f)))
  (when-let [f (:get-item-model-id hooks)]
    (alter-var-root #'*get-item-model-id-hook* (constantly f)))
  nil)

(defn get-all-definitions
  []
  (*get-all-definitions-hook*))

(defn get-block-state-definition
  [block-key]
  (*get-block-state-definition-hook* block-key))

(defn is-multipart-block?
  "Multipart when parts count > 1."
  [definition]
  (*is-multipart-block-hook* definition))

(defn get-model-cube-texture-config
  [model-name]
  (*get-model-cube-texture-config-hook* model-name))

(defn get-model-texture-config
  [model-name]
  (*get-model-texture-config-hook* model-name))

(defn get-item-model-id
  [mod-id registry-name]
  (*get-item-model-id-hook* mod-id registry-name))

;; End of file
