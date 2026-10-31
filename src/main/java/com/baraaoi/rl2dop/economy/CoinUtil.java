package com.baraaoi.rl2dop.economy;

import com.baraaoi.rl2dop.registry.ModItems;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Работа с физическими монетами в инвентаре игрока (только сервер). */
public final class CoinUtil {
	private CoinUtil() {
	}

	public static int count(ServerPlayer player) {
		int total = 0;
		for (ItemStack stack : player.getInventory().items) {
			if (!stack.isEmpty() && stack.is(ModItems.COIN)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	public static boolean take(ServerPlayer player, int amount) {
		if (amount <= 0) {
			return true;
		}
		if (count(player) < amount) {
			return false;
		}
		int left = amount;
		for (int slot = 0; slot < player.getInventory().items.size() && left > 0; slot++) {
			ItemStack stack = player.getInventory().items.get(slot);
			if (stack.isEmpty() || !stack.is(ModItems.COIN)) {
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

	public static void give(ServerPlayer player, int amount) {
		int left = amount;
		while (left > 0) {
			int chunk = Math.min(64, left);
			ItemStack stack = new ItemStack(ModItems.COIN, chunk);
			if (!player.getInventory().add(stack)) {
				player.drop(stack, false);
			}
			left -= chunk;
		}
	}

	public static int countItems(ServerPlayer player, net.minecraft.world.item.Item item) {
		int total = 0;
		for (ItemStack stack : player.getInventory().items) {
			if (!stack.isEmpty() && stack.is(item)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	public static boolean takeItems(ServerPlayer player, net.minecraft.world.item.Item item, int amount) {
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
