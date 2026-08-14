(ns cn.li.ac.ability.client.presentation-effects
  "AC effect controllers for the shared Presentation Runtime.

   Resource IO is injected by minecraft/base; AC only supplies owner/lifecycle
   decisions and parameters."
  (:require [cn.li.presentation.compiler.fx :as fx]
            [cn.li.presentation.core.effects :as effects]))

(def template-resource "assets/academy/presentation/body_intensify.fx.edn")
(def template-resources
  [template-resource
   "assets/academy/presentation/body_intensify_burst.fx.edn"
   "assets/academy/presentation/railgun_charge.fx.edn"])

(defn install-templates! [effect-runtime read-resource]
  (mapv (fn [resource]
          (let [template (fx/compile-edn (read-resource resource))]
            (effects/register-template! effect-runtime template)
            (:id template)))
        template-resources))

(defn start-body-intensify-charge! [effect-runtime owner now-ms params]
  (effects/spawn! effect-runtime :academy/body-intensify-charge owner params now-ms))

(defn start-body-intensify-burst! [effect-runtime owner now-ms params]
  (effects/spawn! effect-runtime :academy/body-intensify-burst owner params now-ms))

(defn start-railgun-charge! [effect-runtime owner now-ms params]
  (effects/spawn! effect-runtime :academy/railgun-charge owner params now-ms))

(defn stop-owner-effects! [effect-runtime owner]
  (effects/clear-owner! effect-runtime owner))

(defn tick! [effect-runtime delta-ms]
  (effects/tick! effect-runtime delta-ms))
