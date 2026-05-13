(ns cn.li.forge1201.registry.fluids
  "Fluid registration for Forge 1.20.1."
  (:require [cn.li.forge1201.integration.bootstrap :as bootstrap]
            [cn.li.forge1201.registry.state :as registry-state]
            [cn.li.mcmod.registry.metadata :as registry-metadata])
  (:import [net.minecraftforge.registries DeferredRegister RegistryObject]))

(defn register-all-fluids!
  [{:keys [fluid-types-register fluids-register items-register]}]
  (doseq [fluid-id (registry-metadata/get-all-fluid-ids)]
    (let [fluid-spec (registry-metadata/get-fluid-spec fluid-id)
          physical (:physical fluid-spec)
          rendering (:rendering fluid-spec)
          behavior (:behavior fluid-spec)
          block-spec (:block fluid-spec)
          registry-name (registry-metadata/get-fluid-registry-name fluid-id)
          flowing-name (str registry-name "_flowing")
          fluid-type-ro (.register ^DeferredRegister fluid-types-register registry-name
                                   (reify java.util.function.Supplier
                                     (get [_]
                                       (bootstrap/create-fluid-type
                                         (:luminosity physical)
                                         (:density physical)
                                         (:viscosity physical)
                                         (:temperature physical)
                                         false
                                         (:supports-boat physical)
                                         (:still-texture rendering)
                                         (:flowing-texture rendering)
                                         (:overlay-texture rendering)
                                         (:tint-color rendering)))))
          source-holder (atom nil)
          flowing-holder (atom nil)
          bucket-holder (atom nil)
          source-ro (.register ^DeferredRegister fluids-register registry-name
                               (reify java.util.function.Supplier
                                 (get [_]
                                   (bootstrap/create-source-fluid
                                     (bootstrap/create-flowing-fluid-properties
                                       (reify java.util.function.Supplier
                                         (get [_] (.get ^RegistryObject fluid-type-ro)))
                                       (reify java.util.function.Supplier
                                         (get [_] (.get ^RegistryObject @source-holder)))
                                       (reify java.util.function.Supplier
                                         (get [_] (.get ^RegistryObject @flowing-holder)))
                                       (when (:has-bucket? block-spec)
                                         (reify java.util.function.Supplier
                                           (get [_] (.get ^RegistryObject @bucket-holder))))
                                       (when-let [block-id (:block-id block-spec)]
                                         (reify java.util.function.Supplier
                                           (get [_]
                                             (.get ^RegistryObject (get @registry-state/registered-blocks block-id)))))
                                       (:slope-find-distance behavior)
                                       (:level-decrease-per-block behavior)
                                       (:tick-rate behavior)
                                       (:explosion-resistance behavior)
                                       (:can-convert-to-source physical))))))
          flowing-ro (.register ^DeferredRegister fluids-register flowing-name
                                (reify java.util.function.Supplier
                                  (get [_]
                                    (bootstrap/create-flowing-fluid
                                      (bootstrap/create-flowing-fluid-properties
                                        (reify java.util.function.Supplier
                                          (get [_] (.get ^RegistryObject fluid-type-ro)))
                                        (reify java.util.function.Supplier
                                          (get [_] (.get ^RegistryObject @source-holder)))
                                        (reify java.util.function.Supplier
                                          (get [_] (.get ^RegistryObject @flowing-holder)))
                                        (when (:has-bucket? block-spec)
                                          (reify java.util.function.Supplier
                                            (get [_] (.get ^RegistryObject @bucket-holder))))
                                        (when-let [block-id (:block-id block-spec)]
                                          (reify java.util.function.Supplier
                                            (get [_]
                                              (.get ^RegistryObject (get @registry-state/registered-blocks block-id)))))
                                        (:slope-find-distance behavior)
                                        (:level-decrease-per-block behavior)
                                        (:tick-rate behavior)
                                        (:explosion-resistance behavior)
                                        (:can-convert-to-source physical))))))]
      (reset! source-holder source-ro)
      (reset! flowing-holder flowing-ro)
      (swap! registry-state/registered-fluid-types assoc fluid-id fluid-type-ro)
      (swap! registry-state/registered-fluids-source assoc fluid-id source-ro)
      (swap! registry-state/registered-fluids-flowing assoc fluid-id flowing-ro)
      (when (:has-bucket? block-spec)
        (let [bucket-ro (.register ^DeferredRegister items-register (:bucket-registry-name block-spec)
                                   (reify java.util.function.Supplier
                                     (get [_]
                                       (bootstrap/create-fluid-bucket
                                         (reify java.util.function.Supplier
                                           (get [_] (.get ^RegistryObject source-ro)))))))]
          (reset! bucket-holder bucket-ro)
          (swap! registry-state/registered-items assoc (:bucket-item-id block-spec) bucket-ro))))))
