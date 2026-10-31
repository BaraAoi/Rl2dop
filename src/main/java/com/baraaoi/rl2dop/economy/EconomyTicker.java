package com.baraaoi.rl2dop.economy;

import com.baraaoi.rl2dop.Rl2dopConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

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
			settlementStep(server, state);
		}

		// таблички с нуждами обновляем чаще, чем рынок, но не каждый тик
		if (Rl2dopConfig.needsOnSigns && server.getTickCount() % Math.max(20, Rl2dopConfig.displayRefreshTicks) == 0) {
			SignBoards.refreshAll(server);
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

	/**
	 * Хозяйственный шаг поселения: таблички нужд, рост построек, глава и расселение по домам.
	 * Нужды появляются сразу при основании — их рождает стройка, а не «пустое место».
	 */
	public static void settlementStep(MinecraftServer server, EconomyState state) {
		for (Settlement settlement : state.all()) {
			ServerLevel level = Displays.levelOf(server, settlement.dimension);
			if (level == null) {
				continue;
			}
			if (settlement.boards.isEmpty()) {
				SignBoards.install(level, settlement);
				state.log("поселение " + settlement.id + ": вывесили таблички нужд (стройка "
						+ settlement.buildProgress + "%)");
			}
			if (Rl2dopConfig.autoBuild && settlement.buildProgress < 100) {
				SettlementBuilds.build(level, settlement, Math.max(1, Rl2dopConfig.buildBlocksPerTick));
			}
			if (!settlement.hasMayor() && SettlementBuilds.hallStanding(settlement)) {
				com.baraaoi.rl2dop.entity.CitizenEntity mayor = SettlementService.spawnMayor(level, settlement);
				if (mayor != null) {
					state.log("поселение " + settlement.id + ": главой стал " + mayor.getCitizenName()
							+ " у табличек");
				}
			}
			if (settlement.buildProgress >= 100) {
				SettlementService.assignHomes(level, settlement);
			}
			state.setDirty();
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
