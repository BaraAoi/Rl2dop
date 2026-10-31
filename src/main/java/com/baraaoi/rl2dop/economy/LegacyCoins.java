package com.baraaoi.rl2dop.economy;

import com.baraaoi.rl2dop.registry.ModItems;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Переезд со старой единственной монеты {@code rl2dop:coin} на три номинала.
 * Старый предмет остаётся зарегистрированным (иначе чанки и сундуки выдают «неизвестный предмет»),
 * но при входе игрока его стопки меняются на бронзу 1:1 — сумма не меняется.
 */
public final class LegacyCoins {
	private LegacyCoins() {
	}

	/** @return сколько монетold было заменено */
	public static int migrate(ServerPlayer player) {
		int moved = 0;
		for (int slot = 0; slot < player.getInventory().items.size(); slot++) {
			moved += replace(player.getInventory().items, slot);
		}
		for (int slot = 0; slot < player.getInventory().offhand.size(); slot++) {
			moved += replace(player.getInventory().offhand, slot);
		}
		if (moved > 0) {
			Money.give(player, moved);
			Money.syncInventory(player);
		}
		return moved;
	}

	private static int replace(java.util.List<ItemStack> list, int slot) {
		ItemStack stack = list.get(slot);
		if (stack.isEmpty() || !stack.is(ModItems.LEGACY_COIN)) {
			return 0;
		}
		int count = stack.getCount();
		list.set(slot, ItemStack.EMPTY);
		return count;
	}
}
