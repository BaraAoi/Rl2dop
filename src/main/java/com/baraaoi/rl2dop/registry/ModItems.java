package com.baraaoi.rl2dop.registry;

import com.baraaoi.rl2dop.Rl2dop;
import com.baraaoi.rl2dop.item.CoinItem;
import com.baraaoi.rl2dop.item.HealItem;
import com.baraaoi.rl2dop.item.LegacyCoinItem;
import com.baraaoi.rl2dop.item.NpcEggItem;
import com.baraaoi.rl2dop.item.WalletItem;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;

public final class ModItems {
	private ModItems() {
	}

	public static final Item BRONZE_COIN = register("bronze_coin", new CoinItem(new Item.Properties()));
	public static final Item IRON_COIN = register("iron_coin", new CoinItem(new Item.Properties()));
	public static final Item GOLD_COIN = register("gold_coin", new CoinItem(new Item.Properties()));
	public static final Item WALLET = register("wallet", new WalletItem(new Item.Properties()));
	public static final Item BANDAGE = register("bandage", new HealItem(4.0F, 40, new Item.Properties()));
	public static final Item MEDKIT = register("medkit", new HealItem(12.0F, 200, new Item.Properties()));
	public static final Item NPC_EGG = register("npc_egg", new NpcEggItem(new Item.Properties().stacksTo(16)));
	/** Старая монета из первых версий: только для миграции, в креативе не светится. */
	public static final Item LEGACY_COIN = register("coin", new LegacyCoinItem(new Item.Properties()));

	private static Item register(String name, Item item) {
		return Registry.register(BuiltInRegistries.ITEM, Rl2dop.id(name), item);
	}

	public static void init() {
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.INGREDIENTS).register(entries -> {
			entries.accept(BRONZE_COIN);
			entries.accept(IRON_COIN);
			entries.accept(GOLD_COIN);
			entries.accept(WALLET);
			entries.accept(BANDAGE);
			entries.accept(MEDKIT);
		});
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.SPAWN_EGGS).register(entries -> entries.accept(NPC_EGG));
	}
}
