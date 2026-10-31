package com.baraaoi.rl2dop.economy;

import com.baraaoi.rl2dop.Rl2dopConfig;
import com.baraaoi.rl2dop.registry.ModItems;
import net.minecraft.world.item.Item;

/**
 * Три номинала монет: бронза (единица счёта), железо и золото.
 * Соотношение берётся из конфига ({@code coinIronValue}, {@code coinGoldValue}),
 * но всегда держится монотонным, чтобы размен имел смысл.
 */
public enum CoinType {
	BRONZE("bronze_coin"),
	IRON("iron_coin"),
	GOLD("gold_coin");

	private final String itemId;

	CoinType(String itemId) {
		this.itemId = itemId;
	}

	public String itemId() {
		return itemId;
	}

	public static int ironValue() {
		return Math.max(2, Rl2dopConfig.coinIronValue);
	}

	public static int goldValue() {
		return Math.max(ironValue() + 1, Rl2dopConfig.coinGoldValue);
	}

	/** Стоимость одной монеты в бронзе. */
	public int value() {
		return switch (this) {
			case BRONZE -> 1;
			case IRON -> ironValue();
			case GOLD -> goldValue();
		};
	}

	public Item item() {
		return switch (this) {
			case BRONZE -> ModItems.BRONZE_COIN;
			case IRON -> ModItems.IRON_COIN;
			case GOLD -> ModItems.GOLD_COIN;
		};
	}

	public static CoinType byIndex(int index) {
		CoinType[] values = values();
		return index >= 0 && index < values.length ? values[index] : BRONZE;
	}

	public String shortLabel() {
		return switch (this) {
			case BRONZE -> "б";
			case IRON -> "ж";
			case GOLD -> "з";
		};
	}
}
