(ns cn.li.mc262.datagen.metadata-resolver
  "Shared datagen metadata resolution helpers for 26.2.

  Registry lookups use Identifier + getValue; tag ingredients go through
  HolderSet via an optional HolderGetter (RecipeProvider.items)."
  (:import [net.minecraft.core HolderGetter]
           [net.minecraft.core.registries BuiltInRegistries Registries]
           [net.minecraft.resources Identifier]
           [net.minecraft.tags TagKey]
           [net.minecraft.world.item Item Items]
           [net.minecraft.world.item.crafting Ingredient]
           [net.minecraft.world.level ItemLike]))

(defn resolve-item
  "Resolve item ID string to Minecraft ItemLike.
   Throws if missing or AIR."
  ^ItemLike [item-id parse-rl-fn]
  (let [^Identifier item-rl (parse-rl-fn item-id)
        ^Item item (.getValue BuiltInRegistries/ITEM item-rl)]
    (when (or (nil? item) (= item Items/AIR))
      (throw (ex-info "Unknown item id in recipe metadata" {:item-id item-id})))
    item))

(defn resolve-tag
  "Resolve tag ID string to TagKey for the item registry."
  ^TagKey [tag-id parse-rl-fn]
  (let [^Identifier tag-rl (parse-rl-fn tag-id)]
    (TagKey/create Registries/ITEM tag-rl)))

(defn ingredient-from-spec
  "Create Ingredient from {:item ...} or {:tag ...}.

  For :tag specs, pass a HolderGetter<Item> as the optional third argument
  (RecipeProvider.items). Without it, tag ingredients cannot be built on 26.2."
  (^Ingredient [spec parse-rl-fn]
   (ingredient-from-spec spec parse-rl-fn nil))
  (^Ingredient [spec parse-rl-fn ^HolderGetter items]
   (cond
     (:item spec)
     (Ingredient/of ^ItemLike (resolve-item (:item spec) parse-rl-fn))

     (:tag spec)
     (do
       (when (nil? items)
         (throw (ex-info "Tag ingredient requires HolderGetter<Item>" {:spec spec})))
       (Ingredient/of (.getOrThrow items ^TagKey (resolve-tag (:tag spec) parse-rl-fn))))

     :else
     (throw (ex-info "Invalid ingredient spec" {:spec spec})))))
