(ns cn.li.mc1201.datagen.metadata-resolver
  "Shared datagen metadata resolution helpers.
  
  Provides platform-agnostic Minecraft registry lookups and ingredient construction
  for recipe and loot table data generation. Used by both Forge and Fabric loaders."
  (:import [net.minecraft.core.registries BuiltInRegistries Registries]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.tags TagKey]
           [net.minecraft.world.item Item Items]
           [net.minecraft.world.item.crafting Ingredient]
           [net.minecraft.world.level ItemLike]))

(defn resolve-item
  "Resolve item ID string to Minecraft ItemLike object.
   
   Args:
     item-id: string like \"modid:item_name\"
   
   Returns: net.minecraft.world.level.ItemLike (typically Item)
   
   Throws: ex-info if item not found in registry or is AIR"
  ^ItemLike [item-id parse-rl-fn]
  (let [^ResourceLocation item-rl (parse-rl-fn item-id)
        ^Item item (.get BuiltInRegistries/ITEM item-rl)]
    (when (or (nil? item) (= item Items/AIR))
      (throw (ex-info "Unknown item id in recipe metadata" {:item-id item-id})))
    item))

(defn resolve-tag
  "Resolve tag ID string to Minecraft TagKey.
   
   Args:
     tag-id: string like \"forge:ingots/copper\"
     parse-rl-fn: function to parse string to ResourceLocation
   
   Returns: net.minecraft.tags.TagKey for item registry"
  ^TagKey [tag-id parse-rl-fn]
  (let [^ResourceLocation tag-rl (parse-rl-fn tag-id)]
    (TagKey/create Registries/ITEM tag-rl)))

(defn ingredient-from-spec
  "Create Minecraft Ingredient from spec map.
   
   Args:
     spec: map with either :item or :tag key
       {:item \"modid:item_name\"} → single item ingredient
       {:tag \"modid:tag_name\"} → tag-based ingredient
     parse-rl-fn: function to parse strings to ResourceLocation
   
   Returns: net.minecraft.world.item.crafting.Ingredient
   
   Throws: ex-info if spec has neither :item nor :tag"
  ^Ingredient [spec parse-rl-fn]
  (cond
    (:item spec)
    (let [^"[Lnet.minecraft.world.level.ItemLike;" item-array
          (into-array ItemLike [(resolve-item (:item spec) parse-rl-fn)])]
      (Ingredient/of item-array))

    (:tag spec)
    (Ingredient/of ^TagKey (resolve-tag (:tag spec) parse-rl-fn))

    :else
    (throw (ex-info "Invalid ingredient spec" {:spec spec}))))
