package com.baraaoi.rl2dop.economy;

import com.baraaoi.rl2dop.net.ModPackets;
import net.minecraft.server.level.ServerPlayer;

/**
 * Операции с кошельком. Всё считается на сервере: клиент присылает только «положить»/«достать»,
 * сервер сам пересчитывает инвентарь и отдаёт новое состояние.
 */
public final class WalletService {
	private WalletService() {
	}

	public static final int ACTION_DEPOSIT = 0;
	public static final int ACTION_WITHDRAW = 1;
	/** Размен: собрать всё в каноничные номиналы (золото 100 → железо 10 → бронза 1). */
	public static final int ACTION_CHANGE = 2;

	public static void open(ServerPlayer player) {
		ModPackets.sendWallet(player);
		ModPackets.syncTo(player);
	}

	public static void handle(ServerPlayer player, int action) {
		boolean ok;
		if (action == ACTION_DEPOSIT) {
			ok = Money.depositAll(player);
			if (!ok) {
				TradeService.message(player, "§cКошелька нет в инвентаре.");
			}
		} else if (action == ACTION_WITHDRAW) {
			ok = Money.withdrawAll(player);
			if (!ok) {
				TradeService.message(player, "§7Кошелёк пуст или его нет в инвентаре.");
			}
		} else {
			int total = Money.total(player);
			Money.setTotal(player, total);
			ok = true;
			TradeService.message(player, "§aРазменяно: §f" + Money.describe(Money.purseCounts(player))
					+ " §a(= " + total + "м)");
		}
		ModPackets.sendWallet(player);
		ModPackets.syncTo(player);
		if (ok) {
			player.getInventory().setChanged();
		}
	}
}
