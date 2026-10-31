package com.baraaoi.rl2dop.client;

import com.baraaoi.rl2dop.net.SyncPayload;

/** Клиентский кэш последних серверных данных (только для отрисовки HUD). */
public final class ClientEconomy {
	private ClientEconomy() {
	}

	public static int coins;
	public static int reputation;
	public static String settlement = "";
	public static String ticker = "";

	public static void update(SyncPayload payload) {
		coins = payload.coins();
		reputation = payload.reputation();
		settlement = payload.settlement();
		ticker = payload.ticker();
	}
}
