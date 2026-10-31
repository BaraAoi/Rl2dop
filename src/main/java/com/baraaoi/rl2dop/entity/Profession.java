package com.baraaoi.rl2dop.entity;

import java.util.Locale;

/**
 * Профессии NPC. У каждой есть рабочая точка, простое действие (производит/потребляет)
 * и зарплата из казны поселения.
 */
public enum Profession {
	FARMER("farmer", "minecraft:wheat", 3, null, 0, 200),
	TRADER("trader", null, 0, null, 0, 0),
	GUARD("guard", null, 0, null, 0, 0),
	LUMBERJACK("lumberjack", "minecraft:oak_log", 2, null, 0, 220),
	COOK("cook", "minecraft:bread", 2, "minecraft:wheat", 3, 240),
	MINER("miner", "minecraft:cobblestone", 4, null, 0, 240),
	RANCHER("rancher", "minecraft:leather", 1, null, 0, 300),
	CRAFTSMAN("craftsman", "minecraft:stick", 4, "minecraft:oak_log", 1, 200),
	BUILDER("builder", null, 0, "minecraft:cobblestone", 4, 260),
	HEALER("healer", "rl2dop:bandage", 1, null, 0, 400);

	private final String id;
	private final String produceItem;
	private final int produceAmount;
	private final String consumeItem;
	private final int consumeAmount;
	private final int workTicks;

	Profession(String id, String produceItem, int produceAmount, String consumeItem, int consumeAmount, int workTicks) {
		this.id = id;
		this.produceItem = produceItem;
		this.produceAmount = produceAmount;
		this.consumeItem = consumeItem;
		this.consumeAmount = consumeAmount;
		this.workTicks = workTicks;
	}

	public String id() {
		return id;
	}

	public String produceItem() {
		return produceItem;
	}

	public int produceAmount() {
		return produceAmount;
	}

	public String consumeItem() {
		return consumeItem;
	}

	public int consumeAmount() {
		return consumeAmount;
	}

	public int workTicks() {
		return workTicks;
	}

	public boolean produces() {
		return produceItem != null && produceAmount > 0;
	}

	public boolean consumes() {
		return consumeItem != null && consumeAmount > 0;
	}

	/** Профессии, которые можно назвать "рабочими" (получают зарплату и работают на точке). */
	public boolean isWorker() {
		return this != TRADER && this != GUARD;
	}

	public String translationKey() {
		return "rl2dop.profession." + id;
	}

	public static Profession byId(String id) {
		if (id == null) {
			return FARMER;
		}
		String normalized = id.toLowerCase(Locale.ROOT);
		for (Profession profession : values()) {
			if (profession.id.equals(normalized) || profession.name().toLowerCase(Locale.ROOT).equals(normalized)) {
				return profession;
			}
		}
		return FARMER;
	}

	/** Список для автоподсказок в командах. */
	public static String[] ids() {
		Profession[] values = values();
		String[] out = new String[values.length];
		for (int i = 0; i < values.length; i++) {
			out[i] = values[i].id;
		}
		return out;
	}
}
