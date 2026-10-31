package com.baraaoi.rl2dop.economy;

import com.baraaoi.rl2dop.Rl2dopConfig;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Map;

/** Серверный такт экономики: не каждый тик, а раз в marketIntervalTicks. */
public final class EconomyTicker {
	private EconomyTicker() {
	}

	public static void onServerTick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 0) {
			return;
		}
		long time = server.overworld().getGameTime();
		EconomyState state = EconomyState.get(server);

		if (state.lastMarketTick < 0) {
			state.lastMarketTick = time;
			state.lastReputationTick = time;
		}

		if (time - state.lastMarketTick >= Rl2dopConfig.marketIntervalTicks) {
			state.lastMarketTick = time;
			MarketMath.updateAll(state);
			Displays.refresh(server, state);
		}

		if (Rl2dopConfig.reputationDecayTicks > 0
				&& time - state.lastReputationTick >= Rl2dopConfig.reputationDecayTicks) {
			state.lastReputationTick = time;
			decayReputation(state);
		}

		// периодический HUD-синк, чтобы цены на экране не отставали
		if (server.getTickCount() % 100 == 0) {
			for (var player : server.getPlayerList().getPlayers()) {
				com.baraaoi.rl2dop.net.ModPackets.syncTo(player);
			}
		}
	}

	private static void decayReputation(EconomyState state) {
		for (Settlement settlement : state.all()) {
			for (Map.Entry<String, Integer> entry : new ArrayList<>(settlement.reputation.entrySet())) {
				int value = entry.getValue();
				if (value > 0) {
					settlement.reputation.put(entry.getKey(), value - 1);
				} else if (value < 0) {
					settlement.reputation.put(entry.getKey(), value + 1);
				}
			}
		}
		state.setDirty();
	}
}
