(ns my-mod.forge1201.client.init
  "Forge 1.20.1 client-side initialization and registration"
  (:require [my-mod.util.log :as log]
            [my-mod.client.render.matrix-renderer :as matrix-renderer])
  (:import [net.minecraftforge.api.distmarker Dist]
           [net.minecraftforge.fml.common.eventhandler SubscribeEvent]
           [net.minecraftforge.fml.event.lifecycle FMLClientSetupEvent]
           [net.minecraftforge.fml.javafxmod FMLJavaModLoadingContext]
           [net.minecraft.client.renderer.blockentity BlockEntityRenderer BlockEntityRenderDispatcher]
           [net.minecraft.world.level.block.entity BlockEntity]))

;; ============================================================================
;; Client Registration
;; ============================================================================

(defn register-renderers
  "Register platform-agnostic renderers with the universal BlockEntityRenderer dispatcher
  
  Called during client setup phase. Registers all block entity renderers
  by invoking their register! functions."
  []
  (log/info "Registering block renderers for Forge 1.20.1...")
  (try
    ;; Register Matrix block renderer
    ;; The matrix-renderer/register! function:
    ;; - Registers TileMatrix with the core renderer dispatcher
    ;; - The universal BlockEntityRenderer (TileEntityRendererImpl class) will dispatch to this renderer
    (matrix-renderer/register!)
    (log/info "Matrix block renderer registered successfully")
    
    (catch Exception e
      (log/error "Failed to register block renderers" e))))

(defn on-client-setup
  "Called when client initialization phase happens
  
  Args:
    event: FMLClientSetupEvent"
  [^FMLClientSetupEvent event]
  (log/info "Client setup event triggered for Forge 1.20.1")
  (register-renderers))

(defn init-client
  "Initialize client-side systems for Forge 1.20.1
  
  Called from mod initialization. Registers event listeners for client setup."
  []
  (log/info "Initializing Forge 1.20.1 client-side systems")
  ;; Register the event handler for client setup
  (-> (FMLJavaModLoadingContext/getInstance)
      (.getModEventBus)
      (.addListener on-client-setup))
  (log/info "Client event handler registered"))
