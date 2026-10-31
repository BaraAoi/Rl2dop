package com.baraaoi.rl2dop.economy;

import com.baraaoi.rl2dop.registry.ModItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Деньги игрока: монеты трёх номиналов и кошелёк.
 *
 * <p>Внутренняя единица — бронза. Кошелёк хранит количество каждой монеты в компоненте
 * {@code custom_data} самого предмета, поэтому переживает перезапуск, копирование и т.п.
 * При любой операции «кошелёк + монеты в карманах» пересчитываются в каноничный набор
 * (золото 100 → железо 10 → бронза 1), так что деньги не размножаются и не пропадают.
 */
public final class Money {
	private Money() {
	}

	// ключи внутри custom_data кошелька
	private static final String BRONZE = "bronze";
	private static final String IRON = "iron";
	private static final String GOLD = "gold";

	public static final int WALLET_SLOTS = 3;

	public static ItemStack findWallet(net.minecraft.world.entity.player.Player player) {
		for (ItemStack stack : player.getInventory().items) {
			if (!stack.isEmpty() && stack.is(ModItems.WALLET)) {
				return stack;
			}
		}
		for (ItemStack stack : player.getInventory().offhand) {
			if (!stack.isEmpty() && stack.is(ModItems.WALLET)) {
				return stack;
			}
		}
		return ItemStack.EMPTY;
	}

	public static int[] walletCounts(ItemStack wallet) {
		int[] counts = new int[CoinType.values().length];
		if (wallet.isEmpty() || !wallet.is(ModItems.WALLET)) {
			return counts;
		}
		CustomData data = wallet.get(DataComponents.CUSTOM_DATA);
		CompoundTag tag = data == null ? new CompoundTag() : data.copyTag();
		counts[CoinType.BRONZE.ordinal()] = Math.max(0, tag.getInt(BRONZE));
		counts[CoinType.IRON.ordinal()] = Math.max(0, tag.getInt(IRON));
		counts[CoinType.GOLD.ordinal()] = Math.max(0, tag.getInt(GOLD));
		return counts;
	}

	public static void setWalletCounts(ItemStack wallet, int[] counts) {
		if (wallet.isEmpty() || !wallet.is(ModItems.WALLET)) {
			return;
		}
		CompoundTag tag = new CompoundTag();
		tag.putInt(BRONZE, Math.max(0, counts[CoinType.BRONZE.ordinal()]));
		tag.putInt(IRON, Math.max(0, counts[CoinType.IRON.ordinal()]));
		tag.putInt(GOLD, Math.max(0, counts[CoinType.GOLD.ordinal()]));
		wallet.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
	}

	public static int value(int[] counts) {
		int total = 0;
		CoinType[] types = CoinType.values();
		for (int i = 0; i < types.length && i < counts.length; i++) {
			total += Math.max(0, counts[i]) * types[i].value();
		}
		return total;
	}

	/** Разложить сумму в бронзе на каноничные номиналы. */
	public static int[] canonical(int bronzeValue) {
		int rest = Math.max(0, bronzeValue);
		int gold = rest / CoinType.GOLD.value();
		rest -= gold * CoinType.GOLD.value();
		int iron = rest / CoinType.IRON.value();
		rest -= iron * CoinType.IRON.value();
		int[] counts = new int[CoinType.values().length];
		counts[CoinType.GOLD.ordinal()] = gold;
		counts[CoinType.IRON.ordinal()] = iron;
		counts[CoinType.BRONZE.ordinal()] = rest;
		return counts;
	}

	/** Сколько всего денег у игрока (кошелёк + монеты в инвентаре), в бронзе. */
	public static int total(ServerPlayer player) {
		return value(purseCounts(player));
	}

	public static int[] purseCounts(ServerPlayer player) {
		int[] counts = new int[CoinType.values().length];
		ItemStack wallet = findWallet(player);
		if (!wallet.isEmpty()) {
			add(counts, walletCounts(wallet));
		}
		for (ItemStack stack : player.getInventory().items) {
			if (stack.isEmpty()) {
				continue;
			}
			for (CoinType type : CoinType.values()) {
				if (stack.is(type.item())) {
					counts[type.ordinal()] += stack.getCount();
				}
			}
		}
		// монета во второй руке тоже деньги — иначе её «не видно» для учёта
		for (ItemStack stack : player.getInventory().offhand) {
			if (stack.isEmpty()) {
				continue;
			}
			for (CoinType type : CoinType.values()) {
				if (stack.is(type.item())) {
					counts[type.ordinal()] += stack.getCount();
				}
			}
		}
		return counts;
	}

	/** Только монеты в карманах (вне кошелька). */
	public static int[] pocketCounts(ServerPlayer player) {
		int[] counts = purseCounts(player);
		ItemStack wallet = findWallet(player);
		if (!wallet.isEmpty()) {
			int[] inWallet = walletCounts(wallet);
			for (int i = 0; i < counts.length; i++) {
				counts[i] = Math.max(0, counts[i] - inWallet[i]);
			}
		}
		return counts;
	}

	/** Записать сумму в кошелёк (если он есть), иначе — монетами в инвентарь. */
	public static void setTotal(ServerPlayer player, int bronzeValue) {
		removeCoinItems(player);
		int[] canonical = canonical(bronzeValue);
		ItemStack wallet = findWallet(player);
		if (!wallet.isEmpty()) {
			setWalletCounts(wallet, canonical);
			syncInventory(player);
			return;
		}
		CoinType[] types = CoinType.values();
		for (int i = types.length - 1; i >= 0; i--) {
			int left = canonical[i];
			while (left > 0) {
				int chunk = Math.min(64, left);
				ItemStack stack = new ItemStack(types[i].item(), chunk);
				if (!player.getInventory().add(stack)) {
					player.drop(stack, false);
				}
				left -= chunk;
			}
		}
		syncInventory(player);
	}

	public static boolean take(ServerPlayer player, int bronzeValue) {
		if (bronzeValue <= 0) {
			return true;
		}
		int total = total(player);
		if (total < bronzeValue) {
			return false;
		}
		setTotal(player, total - bronzeValue);
		return true;
	}

	public static void give(ServerPlayer player, int bronzeValue) {
		if (bronzeValue <= 0) {
			return;
		}
		setTotal(player, total(player) + bronzeValue);
	}

	/** Выдать кошелёк, если у игрока его ещё нет. */
	public static boolean ensureWallet(ServerPlayer player) {
		if (!findWallet(player).isEmpty()) {
			return false;
		}
		ItemStack stack = new ItemStack(ModItems.WALLET);
		if (!player.getInventory().add(stack)) {
			player.drop(stack, false);
		}
		syncInventory(player);
		return true;
	}

	/** Положить все монеты из инвентаря в кошелёк. */
	public static boolean depositAll(ServerPlayer player) {
		if (findWallet(player).isEmpty()) {
			return false;
		}
		setTotal(player, total(player));
		return true;
	}

	/** Достать всё из кошелька монетами в инвентарь. */
	public static boolean withdrawAll(ServerPlayer player) {
		ItemStack wallet = findWallet(player);
		if (wallet.isEmpty()) {
			return false;
		}
		int[] counts = walletCounts(wallet);
		int total = value(counts);
		if (total <= 0) {
			return false;
		}
		setWalletCounts(wallet, new int[CoinType.values().length]);
		syncInventory(player);
		CoinType[] types = CoinType.values();
		for (int i = types.length - 1; i >= 0; i--) {
			int left = counts[i];
			while (left > 0) {
				int chunk = Math.min(64, left);
				ItemStack stack = new ItemStack(types[i].item(), chunk);
				if (!player.getInventory().add(stack)) {
					player.drop(stack, false);
				}
				left -= chunk;
			}
		}
		return true;
	}

	public static String describe(int[] counts) {
		return counts[CoinType.GOLD.ordinal()] + "з "
				+ counts[CoinType.IRON.ordinal()] + "ж "
				+ counts[CoinType.BRONZE.ordinal()] + "б";
	}

	private static void add(int[] target, int[] source) {
		for (int i = 0; i < target.length && i < source.length; i++) {
			target[i] += source[i];
		}
	}

	private static void removeCoinItems(ServerPlayer player) {
		for (int slot = 0; slot < player.getInventory().items.size(); slot++) {
			if (isCoin(player.getInventory().items.get(slot))) {
				player.getInventory().items.set(slot, ItemStack.EMPTY);
			}
		}
		for (int slot = 0; slot < player.getInventory().offhand.size(); slot++) {
			if (isCoin(player.getInventory().offhand.get(slot))) {
				player.getInventory().offhand.set(slot, ItemStack.EMPTY);
			}
		}
		syncInventory(player);
	}

	/** Сообщаем клиенту, что слоты изменились (иначе монеты «пропадут» из вида до следующего открытия инвентаря). */
	public static void syncInventory(ServerPlayer player) {
		player.getInventory().setChanged();
		player.containerMenu.broadcastChanges();
	}

	private static boolean isCoin(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		for (CoinType type : CoinType.values()) {
			if (stack.is(type.item())) {
				return true;
			}
		}
		return false;
	}
}
