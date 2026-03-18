(ns my-mod.client.render.tesr-api
  "Universal TileEntity Special Renderer API
  
  Provides a platform-agnostic interface for block entity rendering.
  Platform-specific TESR implementations dispatch to these methods."
  (:require [my-mod.util.log :as log]
            [my-mod.platform.be :as pbe]))

;; ============================================================================
;; TileEntity Rendering Protocol
;; ============================================================================

(defprotocol ITileEntityRenderer
  "Protocol for rendering BlockEntities (tile entities).

  This protocol is implemented by *renderer objects* stored in the
  `renderer-registry`, not by the tile entities themselves."

  (render-tile [renderer tile-entity partial-ticks pose-stack buffer-source packed-light packed-overlay]
    "Render the tile entity.

    Args:
      renderer: ITileEntityRenderer implementation
      tile-entity: BlockEntity instance being rendered
      partial-ticks: interpolation float
      pose-stack: PoseStack for transformations
      buffer-source: MultiBufferSource / VertexConsumerProvider
      packed-light: int lighting
      packed-overlay: int overlay

    Returns: nil"))

;; ============================================================================
;; Renderer Registry & Dispatcher
;; ============================================================================

(defonce renderer-registry (atom {}))

(defn register-tile-renderer!
  "Register a renderer for a specific TileEntity type
  
  Args:
    tile-class: Class object or symbol for the TileEntity type
    renderer-fn: Function that implements ITileEntityRenderer protocol
  
  Example:
    (register-tile-renderer! TileMatrix my-renderer-obj)"
  [tile-class renderer-fn]
  (log/info "Registering TileEntity renderer for" tile-class)
  (swap! renderer-registry assoc tile-class renderer-fn))

;; Scripted block entities: dispatch by block-id (string). Platform registers
;; one BER for ScriptedBlockEntity and calls render-scripted-tile-entity.
(defonce scripted-renderer-registry (atom {}))

(defn register-scripted-tile-renderer!
  "Register a renderer for a scripted block entity by block-id.
  Used when the BE is ScriptedBlockEntity; platform BER gets block-id and calls
  render-scripted-tile-entity(block-id, be, x, y, z)."
  [block-id renderer-obj]
  (log/info "Registering scripted TileEntity renderer for" block-id)
  (swap! scripted-renderer-registry assoc block-id renderer-obj))

(defn get-scripted-tile-renderer [block-id]
  (get @scripted-renderer-registry block-id))

(defn render-scripted-tile-entity
  "Render a scripted block entity by block-id. Called from platform BER when
  the BE is ScriptedBlockEntity."
  [block-id tile-entity partial-ticks pose-stack buffer-source packed-light packed-overlay]
  (when-let [renderer (get-scripted-tile-renderer block-id)]
    (try
      (render-tile renderer tile-entity partial-ticks pose-stack buffer-source packed-light packed-overlay)
      (catch Exception e
        (log/error "Error rendering scripted tile entity" block-id (.getMessage e))
        (.printStackTrace e)))))

(defn- get-block-id [tile-entity]
  (when tile-entity
    (pbe/get-block-id tile-entity)))

(defn get-tile-renderer
  "Get the renderer for a TileEntity instance.
  For scripted BEs (with getBlockId), looks up scripted-renderer-registry by block-id first."
  [tile-entity]
  (when tile-entity
    (or (when-let [block-id (get-block-id tile-entity)]
          (get-scripted-tile-renderer block-id))
        (get @renderer-registry (class tile-entity)))))

(defn render-tile-entity
  "Universal dispatcher for rendering any TileEntity
  
  Args:
    tile-entity: The TileEntity to render
    partial-ticks: interpolation float
    pose-stack: PoseStack passed from platform
    buffer-source: VertexConsumer provider
    packed-light: lighting
    packed-overlay: overlay
  
  Returns: nil
  
  Dispatches to appropriate renderer based on TileEntity type."
  [tile-entity partial-ticks pose-stack buffer-source packed-light packed-overlay]
  (if-let [renderer (get-tile-renderer tile-entity)]
    (try
      (render-tile renderer tile-entity partial-ticks pose-stack buffer-source packed-light packed-overlay)
      (catch Exception e
        (log/error "Error rendering tile entity:" (.getMessage e))
        (.printStackTrace e)))
    (log/warn "No renderer registered for" (class tile-entity))))
