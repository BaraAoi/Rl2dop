package com.baraaoi.rl2dop.economy;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Работа с обычными предметами в инвентаре игрока (только сервер). */
public final class InventoryUtil {
	private InventoryUtil() {
	}

	public static int countItems(ServerPlayer player, Item item) {
		int total = 0;
		for (ItemStack stack : player.getInventory().items) {
			if (!stack.isEmpty() && stack.is(item)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	public static boolean takeItems(ServerPlayer player, Item item, int amount) {
		if (countItems(player, item) < amount) {
			return false;
		}
		int left = amount;
		for (int slot = 0; slot < player.getInventory().items.size() && left > 0; slot++) {
			ItemStack stack = player.getInventory().items.get(slot);
			if (stack.isEmpty() || !stack.is(item)) {
				continue;
			}
			int take = Math.min(left, stack.getCount());
			stack.shrink(take);
			left -= take;
			if (stack.isEmpty()) {
				player.getInventory().items.set(slot, ItemStack.EMPTY);
			}
		}
		return left == 0;
	}
}
