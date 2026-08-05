(ns cn.li.neoforge262.capability.fluid-handler
  "Forge-specific fluid capability registration for AC machines.

  26.2: expose Capabilities.Fluid.BLOCK (ResourceHandler<FluidResource>) by
  wrapping the existing IFluidHandler through FluidHandlerAsResourceHandler."
  (:require [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.capability.registry :as cap-registry]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.neoforge262.capability CapabilityRegistry
            FluidHandlerAsResourceHandler ForgeProvidedCapabilitySupport]
           [cn.li.neoforge262.shim UniversalFluidHandler]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.resources Identifier]
           [net.minecraft.world.level.material Fluid]
           [net.neoforged.neoforge.fluids FluidStack]
           [net.neoforged.neoforge.fluids.capability IFluidHandler]
           [net.neoforged.neoforge.fluids.capability IFluidHandler$FluidAction]
           [net.neoforged.neoforge.transfer ResourceHandler]))

(def ^:private phase-fluid-lock (Object.))
(def ^:private ^:dynamic *phase-fluid* nil)

(defn- resolve-phase-fluid
  []
  (or *phase-fluid*
      (locking phase-fluid-lock
        (or *phase-fluid*
            (let [{:keys [mod-id path]} (cap-registry/get-tile-fluid-spec "phase-gen")
                  rl (when (and mod-id path)
                       (Identifier/fromNamespaceAndPath ^String mod-id ^String path))
                  fluid (when rl
                          (try (.getValue BuiltInRegistries/FLUID rl)
                               (catch Exception e (log/debug "Fluid registry lookup failed:" (ex-message e)) nil)))]
              (alter-var-root #'*phase-fluid* (constantly fluid))
              fluid)))))

(defn- create-phase-gen-fluid-handler
  "Return an IFluidHandler backed by the phase-gen BE's custom state."
  [be]
  (let [^Fluid fluid (resolve-phase-fluid)]
    (UniversalFluidHandler.
      (fn [] 1)
      (fn [_tank]
        (let [state (platform-be/get-custom-state be)
              amount (int (get state :liquid-amount 0))]
          (if (and fluid (pos? amount))
            (FluidStack. fluid amount)
            FluidStack/EMPTY)))
      (fn [_tank]
        (let [state (platform-be/get-custom-state be)]
          (int (get state :tank-size 8000))))
      (fn [_tank stack]
        (let [^FluidStack stack stack]
          (boolean (and fluid stack (= (.getFluid stack) fluid)))))
      (fn [resource action]
        (let [^FluidStack resource resource
              ^IFluidHandler$FluidAction action action]
          (if (and fluid resource (= (.getFluid resource) fluid))
            (let [state (platform-be/get-custom-state be)
                  amount (.getAmount resource)
                  current (int (get state :liquid-amount 0))
                  capacity (int (get state :tank-size 8000))
                  can-fill (max 0 (min amount (- capacity current)))]
              (when (and (pos? can-fill) (= action IFluidHandler$FluidAction/EXECUTE))
                (let [new-state (assoc state :liquid-amount (+ current can-fill))]
                  (platform-be/set-custom-state! be new-state)
                  (try (platform-be/set-changed! be) (catch Exception e (log/debug "set-changed! failed for fluid fill" (ex-message e)) nil))
                  (try (platform-be/sync-to-client! be) (catch Exception e (log/debug "sync-to-client! failed for fluid fill" (ex-message e)) nil))))
              (int can-fill))
            0)))
      (fn [max-drain action]
        (let [^int max-drain max-drain
              ^IFluidHandler$FluidAction action action
              state (platform-be/get-custom-state be)
              current (int (get state :liquid-amount 0))
              can-drain (min current max-drain)]
          (if (and fluid (pos? can-drain))
            (do
              (when (= action IFluidHandler$FluidAction/EXECUTE)
                (let [new-state (assoc state :liquid-amount (- current can-drain))]
                  (platform-be/set-custom-state! be new-state)
                  (try (platform-be/set-changed! be) (catch Exception e (log/debug "set-changed! failed for fluid drain" (ex-message e)) nil))
                  (try (platform-be/sync-to-client! be) (catch Exception e (log/debug "sync-to-client! failed for fluid drain" (ex-message e)) nil))))
              (FluidStack. fluid can-drain))
            FluidStack/EMPTY)))
      (fn [resource action]
        (let [^FluidStack resource resource
              ^IFluidHandler$FluidAction action action]
          (if (and fluid resource (= (.getFluid resource) fluid))
            (let [state (platform-be/get-custom-state be)
                  amount (.getAmount resource)
                  current (int (get state :liquid-amount 0))
                  can-drain (min current amount)]
              (if (pos? can-drain)
                (do
                  (when (= action IFluidHandler$FluidAction/EXECUTE)
                    (let [new-state (assoc state :liquid-amount (- current can-drain))]
                      (platform-be/set-custom-state! be new-state)
                      (try (platform-be/set-changed! be) (catch Exception e (log/debug "set-changed! failed for fluid energy" (ex-message e)) nil))
                      (try (platform-be/sync-to-client! be) (catch Exception e (log/debug "sync-to-client! failed for fluid energy" (ex-message e)) nil))))
                  (FluidStack. fluid can-drain))
                FluidStack/EMPTY))
            FluidStack/EMPTY))))))

(defn- get-fluid-resource-handler
  [be _side]
  (FluidHandlerAsResourceHandler/wrap (create-phase-gen-fluid-handler be)))

(defn register!
  "Register Capabilities.Fluid.BLOCK for AC machines."
  []
  (CapabilityRegistry/registerBlock "fluid-handler" (ForgeProvidedCapabilitySupport/fluidBlock))
  (when-not (cap-registry/get-capability-entry :fluid-handler)
    (cap-registry/declare-capability!
      :fluid-handler ResourceHandler
      get-fluid-resource-handler))
  (log/info "Registered NeoForge Capabilities.Fluid.BLOCK for AC machines"))

(defn init! [& _] (register!))
