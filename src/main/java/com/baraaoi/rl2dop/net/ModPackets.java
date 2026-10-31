package com.baraaoi.rl2dop.net;

import com.baraaoi.rl2dop.Rl2dopConfig;
import com.baraaoi.rl2dop.economy.CoinUtil;
import com.baraaoi.rl2dop.economy.EconomyState;
import com.baraaoi.rl2dop.economy.Settlement;
import com.baraaoi.rl2dop.economy.SettlementService;
import com.baraaoi.rl2dop.economy.TradeService;
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
		PayloadTypeRegistry.playC2S().register(TradeActionPayload.TYPE, TradeActionPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(TradeActionPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> TradeService.handle(player, payload.citizenId(), payload.itemId(),
					payload.amount(), payload.action()));
		});
	}

	public static void syncTo(ServerPlayer player) {
		EconomyState state = EconomyState.get(player.serverLevel().getServer());
		Settlement settlement = SettlementService.forPlayer(player);
		int coins = CoinUtil.count(player);
		int reputation = settlement == null ? 0 : settlement.reputation(player.getUUID());
		String name = settlement == null ? "" : settlement.displayName();
		String ticker = "эмиссия " + state.minted + "/" + Rl2dopConfig.mintLimit
				+ (settlement == null ? "" : " · казна " + settlement.treasury + "м");
		ServerPlayNetworking.send(player, new SyncPayload(coins, reputation, name, ticker));
	}

	public static void sendShop(ServerPlayer player, int citizenId, String title, List<String> goods) {
		ServerPlayNetworking.send(player, new ShopPayload(citizenId, title, new ArrayList<>(goods)));
	}

	public static void clearPlayer(UUID uuid) {
		TradeService.clearPlayer(uuid);
	}
}
