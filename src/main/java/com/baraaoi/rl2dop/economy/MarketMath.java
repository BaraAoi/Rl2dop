package com.baraaoi.rl2dop.economy;

import com.baraaoi.rl2dop.Rl2dopConfig;

/**
 * Динамическая цена: {@code base * (targetStock / stock)^elasticity * need * reputation}.
 * Все значения зажаты в [min, max], а итоговая цена плавно (smoothing) догоняет целевую —
 * поэтому нет бесконечных скачков.
 */
public final class MarketMath {
	private MarketMath() {
	}

	public static double targetPrice(Settlement settlement, Rl2dopConfig.Good good) {
		int stock = settlement.stock(good.item);
		double ratio = (double) Math.max(1, good.targetStock) / (double) Math.max(1, stock);
		double scarcity = clamp(ratio, 0.2D, 5.0D);
		double need = 1.0D + Math.min(1.5D, settlement.demand(good.item) / 100.0D);
		double raw = good.base * Math.pow(scarcity, Math.max(0.2D, good.elasticity)) * need;
		return clamp(raw, good.min, good.max);
	}

	/** Репутация: хорошему — скидка, злому — наценка. */
	public static double reputationFactor(int reputation) {
		int clamped = Math.max(-100, Math.min(100, reputation));
		return 1.0D - (clamped / 100.0D) * 0.15D;
	}

	public static int buyPrice(Settlement settlement, Rl2dopConfig.Good good, int reputation, double haggleFactor) {
		double price = settlement.price(good.item) * (1.0D + Rl2dopConfig.buySpread)
				* reputationFactor(reputation) * haggleFactor;
		return clampToInt(price, good.min, good.max);
	}

	public static int sellPrice(Settlement settlement, Rl2dopConfig.Good good, int reputation, double haggleFactor) {
		double price = settlement.price(good.item) * (1.0D - Rl2dopConfig.sellSpread)
				* reputationFactor(reputation) * haggleFactor;
		return Math.max(good.min > 0 ? 1 : 0, (int) Math.floor(price));
	}

	public static void updateAll(EconomyState state) {
		for (Settlement settlement : state.all()) {
			for (Rl2dopConfig.Good good : Rl2dopConfig.goods.values()) {
				if (Rl2dopConfig.demandDecayPerCycle > 0) {
					settlement.addDemand(good.item, -Rl2dopConfig.demandDecayPerCycle);
				}
				double current = settlement.price(good.item);
				double target = targetPrice(settlement, good);
				double smoothing = clamp(Rl2dopConfig.priceSmoothing, 0.01D, 1.0D);
				double next = current + (target - current) * smoothing;
				settlement.prices.put(good.item, clamp(next, good.min, good.max));
			}
		}
		state.setDirty();
	}

	public static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	public static int clampToInt(double value, double min, double max) {
		return (int) Math.round(Math.max(min, Math.min(max, value)));
	}
}
