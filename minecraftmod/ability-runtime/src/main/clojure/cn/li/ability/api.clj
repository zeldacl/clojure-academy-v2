(ns cn.li.ability.api
  "The public surface of ability-runtime -- the only namespace a content
   module (ac, and future bc/cc) may require from this module.

   Sized from ac's actual four consumption points (core/init.clj,
   server_hooks.clj, final_catalog.clj, gui/reactive/register.clj), not
   speculative coverage.

   This facade's surface is deliberately stable across the P4 multi-tenant
   refactor: ac calls install-runtime!/create-runtime/schedule-installed!/
   compose-catalog/merge-vfx-into-frame etc. today against a single-tenant
   singleton (cn.li.ability.combat's runtime* atom, cn.li.ability.compose's
   one-bundle compose-catalog); P4 replaces those internals with a
   multi-tenant registry without changing what a content module calls
   through here."
  (:require [cn.li.ability.combat :as combat]
            [cn.li.ability.compose :as compose]))

;; ---- combat continuation runtime (installed once at composition time) ----
(defn install-runtime! [runtime] (combat/install-runtime! runtime))
(defn create-runtime [opts] (combat/create-runtime opts))
(defn schedule-installed! [request] (combat/schedule-installed! request))
(defn tick-installed-owner! [owner] (combat/tick-installed-owner! owner))
(defn cancel-installed-owner! [owner] (combat/cancel-installed-owner! owner))
(defn cancel-installed-all! [] (combat/cancel-installed-all!))

;; ---- cross-core catalog composition + frame assembly ----
(defn compose-catalog [content-id node-environment combat-content vfx-content]
  (compose/compose-catalog content-id node-environment combat-content vfx-content))
(defn catalog-fingerprint-input [bundle] (compose/catalog-fingerprint-input bundle))
(defn merge-draw-lists [generation draw-lists] (compose/merge-draw-lists generation draw-lists))
(defn merge-vfx-into-frame [ui-packet vfx-frame] (compose/merge-vfx-into-frame ui-packet vfx-frame))
