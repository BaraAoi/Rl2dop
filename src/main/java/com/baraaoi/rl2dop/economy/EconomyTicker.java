package com.baraaoi.rl2dop.economy;

import com.baraaoi.rl2dop.Rl2dopConfig;
import com.baraaoi.rl2dop.entity.Profession;
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
			if (settlement.buildProgress < 100) {
				// ратуша не «строится» — поселение рождается с готовым зданием и постройками;
				// постепенная стройка остаётся только если instantTownHall=false
				if (Rl2dopConfig.instantTownHall || !Rl2dopConfig.autoBuild) {
					SettlementBuilds.buildInstant(level, settlement);
				} else {
					SettlementBuilds.build(level, settlement, Math.max(1, Rl2dopConfig.buildBlocksPerTick));
				}
			}
			// глава нужен поселению с первого дня: без ратуши он стоит у временных табличек
			if (!settlement.hasMayor()) {
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
		workshopStep(server, state);
	}

	/**
	 * Мастерские работают сами. Каждое рабочее строение раз в цикл рынка даёт продукт, если в
	 * поселении есть работник этой профессии, — и потребляет сырьё, поднимая на него спрос.
	 * Так шахта, ферма, лесопилка и мастерская полезны, даже если житель ещё идёт к ним.
	 */
	private static void workshopStep(MinecraftServer server, EconomyState state) {
		if (!Rl2dopConfig.workshopsAutoWork) {
			return;
		}
		for (Settlement settlement : state.all()) {
			if (settlement.workSites.isEmpty()) {
				continue;
			}
			ServerLevel level = Displays.levelOf(server, settlement.dimension);
			if (level == null) {
				continue;
			}
			for (Map.Entry<String, String> site : new ArrayList<>(settlement.workSites.entrySet())) {
				Profession profession = Profession.byId(site.getKey());
				int workers = settlement.jobCount(profession);
				if (workers <= 0) {
					continue;
				}
				if (profession.consumes()) {
					String input = profession.consumeItem();
					int need = Math.max(1, profession.consumeAmount() * workers);
					if (settlement.warehouse(input) >= need) {
						settlement.takeWarehouse(input, need);
					} else if (settlement.stock(input) >= need) {
						settlement.takeStock(input, need);
					} else {
						// нечего переработать — мастерская встаёт, и табличка это показывает
						settlement.addDemand(input, Math.max(1, need / 2));
						continue;
					}
				}
				if (profession.produces()) {
					int amount = profession.produceAmount() * workers;
					settlement.addStock(profession.produceItem(), amount);
					settlement.addDemand(profession.produceItem(), -amount);
				}
				if (profession == Profession.MINER) {
					int extra = Math.max(0, Rl2dopConfig.workshopYield) * workers;
					settlement.addStock("minecraft:cobblestone", extra);
					settlement.addDemand("minecraft:cobblestone", -extra);
					if (level.random.nextInt(3) == 0) {
						settlement.addStock("minecraft:coal", workers);
						settlement.addDemand("minecraft:coal", -workers);
					}
					if (level.random.nextInt(4) == 0) {
						settlement.addStock("minecraft:iron_ingot", workers);
						settlement.addDemand("minecraft:iron_ingot", -workers);
					}
				}
				if (profession == Profession.FARMER && level.random.nextInt(2) == 0) {
					settlement.addStock("minecraft:carrot", workers);
					settlement.addDemand("minecraft:carrot", -workers);
				}
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
