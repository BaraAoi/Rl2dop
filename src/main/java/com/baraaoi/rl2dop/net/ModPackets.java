package com.baraaoi.rl2dop.net;

import com.baraaoi.rl2dop.Rl2dopConfig;
import com.baraaoi.rl2dop.economy.EconomyState;
import com.baraaoi.rl2dop.economy.Money;
import com.baraaoi.rl2dop.economy.Settlement;
import com.baraaoi.rl2dop.economy.SettlementService;
import com.baraaoi.rl2dop.economy.TradeService;
import com.baraaoi.rl2dop.economy.WalletService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ModPackets {
	private ModPackets() {
	}

	public static void init() {
		PayloadTypeRegistry.playS2C().register(SyncPayload.TYPE, SyncPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(ShopPayload.TYPE, ShopPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(WalletPayload.TYPE, WalletPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(TradeActionPayload.TYPE, TradeActionPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(WalletActionPayload.TYPE, WalletActionPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(TradeActionPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> TradeService.handle(player, payload.citizenId(), payload.itemId(),
					payload.amount(), payload.action()));
		});
		ServerPlayNetworking.registerGlobalReceiver(WalletActionPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> WalletService.handle(player, payload.action()));
		});
	}

	public static void syncTo(ServerPlayer player) {
		EconomyState state = EconomyState.get(player.serverLevel().getServer());
		Settlement settlement = SettlementService.forPlayer(player);
		int coins = Money.total(player);
		int[] counts = Money.purseCounts(player);
		int reputation = settlement == null ? 0 : settlement.reputation(player.getUUID());
		String name = settlement == null ? "" : settlement.displayName();
		String ticker = Money.describe(counts)
				+ " · эмиссия " + state.minted + "/" + Rl2dopConfig.mintLimit
				+ (settlement == null ? "" : " · казна " + settlement.treasury + "м");
		ServerPlayNetworking.send(player, new SyncPayload(coins, reputation, name, ticker,
				!Money.findWallet(player).isEmpty()));
	}

	public static void sendShop(ServerPlayer player, int citizenId, String title, List<String> goods) {
		int[] counts = Money.purseCounts(player);
		String purse = Money.describe(counts) + " = " + Money.value(counts) + "м";
		ServerPlayNetworking.send(player, new ShopPayload(citizenId, title, purse, new ArrayList<>(goods)));
	}

	public static void sendWallet(ServerPlayer player) {
		var wallet = Money.findWallet(player);
		int[] inWallet = Money.walletCounts(wallet);
		int[] inPockets = Money.pocketCounts(player);
		String summary = "монеты в карманах: " + Money.describe(inPockets)
				+ " · всего с кошельком: " + Money.total(player) + "м"
				+ (wallet.isEmpty() ? " · кошелька нет" : "");
		ServerPlayNetworking.send(player, new WalletPayload(
				inWallet[0], inWallet[1], inWallet[2], summary));
	}

	public static void clearPlayer(UUID uuid) {
		TradeService.clearPlayer(uuid);
	}
}
