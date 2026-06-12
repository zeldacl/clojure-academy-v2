(ns cn.li.forge1201.capability.fluid-handler
  "Forge-specific IFluidHandler registration for AC machines.

  Called during Forge platform init (before content activation) so that
  `:fluid-handler` capability factories are available when AC block init
  calls `register-tile-capability!`."
  (:require [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.capability :as platform-cap]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.forge1201.capability CapabilityRegistry]
           [net.minecraft.resources ResourceLocation]
           [net.minecraftforge.common.capabilities ForgeCapabilities]
           [net.minecraftforge.fluids FluidStack]
           [net.minecraftforge.fluids.capability IFluidHandler]
           [net.minecraftforge.fluids.capability IFluidHandler$FluidAction]
           [net.minecraftforge.registries ForgeRegistries]))

;; ============================================================================
;; Phase-liquid fluid reference (lazy)
;; ============================================================================

(def ^:private phase-fluid-lock (Object.))
(def ^:private ^:dynamic *phase-fluid* nil)

(defn- resolve-phase-fluid
  []
  (or *phase-fluid*
      (locking phase-fluid-lock
        (or *phase-fluid*
            (let [rl (ResourceLocation. "my_mod" "imag_phase")
                  fluid (try (.getValue ForgeRegistries/FLUIDS rl)
                             (catch Exception _ nil))]
              (alter-var-root #'*phase-fluid* (constantly fluid))
              fluid)))))

;; ============================================================================
;; Phase Generator IFluidHandler
;; ============================================================================

(defn- create-phase-gen-fluid-handler
  "Return an IFluidHandler backed by the phase-gen BE's custom state.
  Reads/writes `:liquid-amount` and `:tank-size` from the schema state map.
  Only accepts `my_mod:imag_phase` fluid (parity: original TilePhaseGen only
  accepted ACFluids.fluidImagProj)."
  [be]
  (let [fluid (resolve-phase-fluid)]
    (reify IFluidHandler
      (getTanks [_] 1)

      (getFluidInTank [_ _tank]
        (let [state (platform-be/get-custom-state be)
              amount (int (get state :liquid-amount 0))]
          (if (and fluid (pos? amount))
            (FluidStack. fluid amount)
            FluidStack/EMPTY)))

      (getTankCapacity [_ _tank]
        (let [state (platform-be/get-custom-state be)]
          (int (get state :tank-size 8000))))

      (isFluidValid [_ _tank stack]
        (boolean (and fluid stack (= (.getFluid stack) fluid))))

      (fill [_ resource action]
        (if (and fluid resource (= (.getFluid resource) fluid))
          (let [state (platform-be/get-custom-state be)
                amount (.getAmount resource)
                current (int (get state :liquid-amount 0))
                capacity (int (get state :tank-size 8000))
                can-fill (max 0 (min amount (- capacity current)))]
            (when (and (pos? can-fill) (= action IFluidHandler$FluidAction/EXECUTE))
              (let [new-state (assoc state :liquid-amount (+ current can-fill))]
                (platform-be/set-custom-state! be new-state)
                (try (platform-be/set-changed! be) (catch Exception _ nil))))
            (int can-fill))
          0))

      (^FluidStack drain [_ ^int max-drain ^IFluidHandler$FluidAction action]
        (let [state (platform-be/get-custom-state be)
              current (int (get state :liquid-amount 0))
              can-drain (min current (int max-drain))]
          (if (and fluid (pos? can-drain))
            (do
              (when (= action IFluidHandler$FluidAction/EXECUTE)
                (let [new-state (assoc state :liquid-amount (- current can-drain))]
                  (platform-be/set-custom-state! be new-state)
                  (try (platform-be/set-changed! be) (catch Exception _ nil))))
              (FluidStack. fluid can-drain))
            FluidStack/EMPTY)))

      (^FluidStack drain [_ ^FluidStack resource ^IFluidHandler$FluidAction action]
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
                    (try (platform-be/set-changed! be) (catch Exception _ nil))))
                (FluidStack. fluid can-drain))
              FluidStack/EMPTY))
          FluidStack/EMPTY)))))

;; ============================================================================
;; Registration (called from init.clj before content activation)
;; ============================================================================

(defn register!
  "Register IFluidHandler capability for AC machines.
  1. Maps Forge's FLUID_HANDLER Capability token to \"fluid-handler\" key.
  2. Declares the Clojure-side capability factory.
  Must be called before any content init that calls register-tile-capability!."
  []
  ;; 1. Map the Forge Capability token to the string key used by tile-logic.
  (CapabilityRegistry/register "fluid-handler" ForgeCapabilities/FLUID_HANDLER)
  ;; 2. Declare the Clojure-side handler factory.
  (when-not (platform-cap/get-capability-entry :fluid-handler)
    (platform-cap/declare-capability!
      :fluid-handler IFluidHandler
      (fn [be _side] (create-phase-gen-fluid-handler be))))
  (log/info "Registered Forge fluid-handler capability for AC machines"))
