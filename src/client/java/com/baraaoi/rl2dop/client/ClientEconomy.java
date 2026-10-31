package com.baraaoi.rl2dop.client;

import com.baraaoi.rl2dop.net.SyncPayload;
import com.baraaoi.rl2dop.net.WalletPayload;

/** Клиентский кэш последних серверных данных (только для отрисовки HUD). */
public final class ClientEconomy {
	private ClientEconomy() {
	}

	public static int coins;
	public static int reputation;
	public static String settlement = "";
	public static String ticker = "";
	public static boolean wallet;
	public static int walletBronze;
	public static int walletIron;
	public static int walletGold;
	public static String walletSummary = "";

	public static void update(WalletPayload payload) {
		walletBronze = payload.bronze();
		walletIron = payload.iron();
		walletGold = payload.gold();
		walletSummary = payload.summary();
	}

	public static void update(SyncPayload payload) {
		coins = payload.coins();
		reputation = payload.reputation();
		settlement = payload.settlement();
		ticker = payload.ticker();
		wallet = payload.wallet();
	}
}
