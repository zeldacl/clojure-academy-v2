(ns my-mod.client.render.tesr-api
  "Universal TileEntity Special Renderer API
  
  Provides a platform-agnostic interface for block entity rendering.
  Platform-specific TESR implementations dispatch to these methods."
  (:require [my-mod.util.log :as log]))

;; ============================================================================
;; TileEntity Rendering Protocol
;; ============================================================================

(defprotocol ITileEntityRenderer
  "Protocol for rendering any TileEntity type
  
  Implementations should provide rendering logic for their specific tile entity."
  
  (render-tile [tile-entity x y z]
    "Render the tile entity at the given world position
    
    Args:
      tile-entity: The TileEntity object to render
      x, y, z: World coordinates (floats)
    
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
    (register-tile-renderer! TileMatrix matrix-renderer-impl)"
  [tile-class renderer-fn]
  (log/info "Registering TileEntity renderer for" tile-class)
  (swap! renderer-registry assoc tile-class renderer-fn))

(defn get-tile-renderer
  "Get the renderer for a TileEntity instance
  
  Args:
    tile-entity: The TileEntity object
  
  Returns: 
    The registered renderer function, or nil if not found"
  [tile-entity]
  (when tile-entity
    (let [tile-class (class tile-entity)]
      (get @renderer-registry tile-class))))

(defn render-tile-entity
  "Universal dispatcher for rendering any TileEntity
  
  Args:
    tile-entity: The TileEntity to render
    x, y, z: World coordinates
  
  Returns: nil
  
  Dispatches to appropriate renderer based on TileEntity type."
  [tile-entity x y z]
  (if-let [renderer (get-tile-renderer tile-entity)]
    (try
      (render-tile renderer tile-entity x y z)
      (catch Exception e
        (log/error "Error rendering tile entity:" (.getMessage e))
        (.printStackTrace e)))
    (log/warn "No renderer registered for" (class tile-entity))))
