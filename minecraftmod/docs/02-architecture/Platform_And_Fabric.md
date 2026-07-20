# 骞冲彴瀹炵幇涓?Fabric 璇存槑

鏈枃妗ｅ悎骞惰嚜骞冲彴瀹炵幇鎸囧崡涓?Fabric 鐩稿叧璇存槑銆?*鏃ュ父寮€鍙戜笌榛樿鏋勫缓浠?Forge 1.20.1 涓轰富**锛沗fabric target` 宸茬撼鍏ユ牴 `settings.gradle`锛屽苟浠?**minimal maintenance** 绾у埆鍙備笌 compile 鍩虹嚎楠岃瘉銆?
> **鏋舵瀯宸插畬鎴?DRY 鍏变韩瀹夎鍣ㄨ縼绉伙紙Batches A-F锛夈€?*
> 鍏叡瀹夎閫昏緫闆嗕腑浜?`platform-src/minecraft/version/mc-1201`锛汧orge 涓?Fabric 鍚勮嚜浠呬繚鐣欏钩鍙扮鏈夐儴鍒嗐€?
---

## 璁捐鍘熷垯

- 骞冲彴浠ｇ爜**涓嶅啓姝绘父鎴忓唴璧勬簮 ID**锛涘唴瀹归€氳繃 `mcmod` 鍏冩暟鎹笌 registry 鍙戠幇銆?- **Forge**锛欽ava 鍏ュ彛 鈫?`cn.li.forge1201.*` 鈫?`mcmod` / `ac`銆?- **Fabric**锛欽ava `ModInitializer` 鈫?`cn.li.fabric1201.*`锛屾棤鍘嗗彶 `my_mod.*` 娣锋帓銆?- **鍏变韩閫昏緫**锛氶€氳繃 `cn.li.mc1201.installer` 鎻愪緵鐨勭粺涓€瀹夎鍣紝涓ゅ骞冲彴鍧囧鎵樺悓涓€鍑芥暟闆嗗畬鎴愬崗璁畨瑁呬笌 var-root 閰嶇疆銆?
---

## 骞冲彴缁撴瀯锛堝綋鍓嶇姸鎬侊級
```
platform-src/minecraft/version/mc-1201/src/main/clojure/cn/li/mc1201/
鈹溾攢鈹€ installer.clj                   # 钖勯棬闈紝杞彂鑷?bootstrap/installer_core.clj
鈹溾攢鈹€ bootstrap/installer_core.clj    # 鍏ㄩ噺鍏变韩瀹夎閫昏緫
鈹溾攢鈹€ platform_adapter.clj            # PlatformAdapter 鍗忚锛團orge/Fabric 鍚勮嚜瀹炵幇锛?鈹溾攢鈹€ reflect_util.clj                # 鍙嶅皠宸ュ叿锛坈lass-noinit 绛夛級
鈹溾攢鈹€ block/, registry/, runtime/, gui/  # 鍏变韩涓氬姟宸ュ叿
鈹斺攢鈹€ ...

platform-src/loader/forge/src/main/clojure/cn/li/forge1201/
鈹溾攢鈹€ platform/platform/init.clj    # SPI 瑙﹀彂闂ㄩ潰锛堜富鍒濆鍖栭摼璋冪敤锛?鈹溾攢鈹€ platform/platform/init.clj      # Forge PlatformAdapter 瀹炵幇 + 绉佹湁鍗忚 extend
鈹溾攢鈹€ integration/imc_dispatch.clj    # Forge IMC 浜嬩欢鍒嗗彂妗ユ帴
鈹斺攢鈹€ ...锛堝叏涓?Forge 绉佹湁锛歟ntity, gui, events, runtime 绛夛級

platform-src/loader/fabric/src/main/clojure/cn/li/fabric1201/
鈹溾攢鈹€ platform/platform/init.clj    # SPI 瑙﹀彂闂ㄩ潰锛堜富鍒濆鍖栭摼璋冪敤锛?鈹溾攢鈹€ platform/platform/init.clj      # Fabric PlatformAdapter 瀹炵幇 + 绉佹湁鍗忚 extend
鈹斺攢鈹€ ...锛堝叏涓?Fabric 绉佹湁锛歜lock, gui, client, datagen 绛夛級
```

### 瀹夎鍣ㄨ皟鐢ㄥ簭鍒?
```
Forge:
	install-foundation!
	install-entity-protocols-only!
	install-item-protocols-only!
	install-block-state-protocol-only!
	install-resource-factory-only!
	install-world-fns-only!
	install-be-fns-only!

Fabric:
	install-platform-core!(adapter)
	install-be-fns-only!(fns-map)    # 鐢?install-be-ops! 鍖呰璋冪敤
```

`platform/platform/init.clj` 璋冪敤 `installer/install-be-fns-only!` 骞朵紶鍏ュ钩鍙扮鏈?BE 绫荤殑 lambda锛沗extend` 璋冪敤淇濈暀鍦ㄥ悇骞冲彴鏈湴锛堢被鍨嬬鏈夛紝鏃犳硶鍏变韩锛夈€俙platform/platform/init.clj` 浠呰礋璐ｉ€氳繃 SPI 瑙﹀彂骞冲彴瀹夎銆?
- **璧勬簮**锛歚platform-src/loader/forge/src/main/resources/META-INF/mods.toml` 绛夛紱娓告垙璧勬簮涔熷彲鍦?`ac/src/main/resources/assets/<mod_id>/` 缁存姢銆?
### Fabric 瀛愬伐绋嬶紙褰撳墠宸茬撼鍏ユ牴鏋勫缓锛?
浠撳簱涓?`platform-src/loader/fabric/` 鍐呭惈 `fabric.mod.json`銆丣ava 鍏ュ彛涓?Clojure 閫傞厤銆?*宸茬Щ闄ゅ巻鍙插崰浣嶇 stub锛坄platform/nbt.clj` 绛?5 涓枃浠讹級**锛孎abric 骞冲彴瀹夎鐜板叏閲忓鎵樺叡浜畨瑁呭櫒銆傚綋鍓嶇瓥鐣ワ細

- 鑷冲皯淇濇寔 compile 绾у彲鐢紙`verifyFabricBaseline`锛夈€?- 涓?Forge 涓嶆壙璇哄畬鍏ㄥ姛鑳藉榻愶紱鑳藉姏宸紓鎸夊綋鍓嶅疄鐜颁笌娴嬭瘯鐭╅樀缁存姢銆?
---

## mod.clj 瑕佺偣锛團orge锛?
- 鍔ㄦ€佹柟鍧?鐗╁搧娉ㄥ唽锛氫粠 `cn.li.mcmod.protocol.metadata` 绛夎幏鍙?ID 涓庤鏍笺€?- BlockItem 绛変负闇€瑕佺墿鍝佺殑鏂瑰潡缁熶竴鍒涘缓骞舵敞鍐屻€?- 涓嶄緷璧栨紨绀虹敤纭紪鐮佹ā鍧楋紱浠ュ厓鏁版嵁涓哄噯銆?
---

## 骞冲彴瀵硅薄鍗忚杈圭晫

- **`cn.li.mcmod.platform.item`**锛堝巻鍙叉枃妗ｆ浘鍐欎綔 `cn.li.platform.item`锛夋槸璺ㄥ钩鍙?`ItemStack` 鎶借薄銆?- 褰撳墠骞冲彴瀹夎鍏ュ彛缁熶竴涓?`cn.li.<loader>.platform.bootstrap-entry`锛岀湡瀹炲崗璁墿灞曚綅浜?`cn.li.<loader>.platform.spi-bootstrap`銆?- `mcmod` 涓?`ac` 閫氳繃涓婅堪鍗忚璁块棶鐗╁搧锛堝 `item-is-empty?`銆乣item-save-to-nbt` 绛夛級锛岄伩鍏嶅湪鍐呭灞傜洿鎺ヤ緷璧?Minecraft 绫汇€?
---

## 浜嬩欢涓?GUI

- **浜嬩欢**锛歚cn.li.mcmod.block.query` 璇嗗埆鏂瑰潡骞惰В鏋愬鐞嗗櫒锛堝 `:on-right-click`锛夛紝`cn.li.mcmod.events.dispatcher` 缁熶竴鍒嗗彂銆?- **GUI**锛欴SL 涓庡厓鏁版嵁鍦?`mcmod`锛涘叿浣?Wireless 绛夊睆骞曞伐鍘備笌瀹瑰櫒閫昏緫澶氬湪 **`ac`**锛堝 `cn.li.ac.wireless.gui.*`銆乣cn.li.ac.block.wireless-node.gui`锛夈€侳orge 璐熻矗 MenuType 娉ㄥ唽涓庢ˉ鎺ョ被銆?
---

## Fabric 涓?Forge 宸紓鎽樿锛堝弬鑰冿級

褰撳墠浠撳簱涓?Fabric 宸茬撼鍏ユ牴鏋勫缓锛涘吀鍨嬪钩鍙板樊寮傚涓嬶紙涓?Minecraft 1.20.1 API 褰㈡€佷竴鑷达級锛?
| 姒傚康     | Forge 1.20.1            | Fabric 1.20.1            |
|----------|-------------------------|--------------------------|
| 瀹瑰櫒     | AbstractContainerMenu   | ScreenHandler            |
| 绫诲瀷娉ㄥ唽 | MenuType                | ScreenHandlerType        |
| 鎵撳紑 GUI | NetworkHooks.openScreen | openHandledScreen 绛?    |

---

## 鍙傝€?
- 椤圭洰鎬荤粨锛歚01-overview/Project_Summary_CN.md`
- GUI 鏋舵瀯锛歚06-gui/GUI_Architecture_Refactoring.md`
- DataGenerator锛歚04-datagen/DataGenerator.md`
