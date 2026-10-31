package com.baraaoi.rl2dop;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Все коэффициенты экономики в одном месте. Значения читаются из config/rl2dop.json
 * (создаётся при первом запуске) и правятся без перекомпиляции мода.
 */
public final class Rl2dopConfig {
	private Rl2dopConfig() {
	}

	// --- деньги ---
	public static int mintLimit = 100_000;
	public static int startingCoins = 0;

	// --- торговля ---
	public static double buySpread = 0.15;
	public static double sellSpread = 0.10;
	public static double shopRange = 10.0;
	public static int tradeCooldownMs = 300;

	// --- рынок ---
	public static int marketIntervalTicks = 100;
	public static double priceSmoothing = 0.30;
	public static int demandDecayPerCycle = 2;
	public static int reputationDecayTicks = 6000;

	// --- NPC ---
	public static int brainIntervalTicks = 10;
	public static int salaryPeriodTicks = 2400;
	public static int autoProfessionCooldownTicks = 1200;
	public static double autoProfessionGain = 1.25;
	public static int maxNpcsPerSettlement = 64;
	public static int displayRefreshTicks = 100;

	// --- репутация ---
	public static int evilReputation = -30;
	public static int shopRefuseReputation = -60;
	public static int npcHitReputationPenalty = 5;

	// --- прочее ---
	public static int defaultSalary = 12;
	public static int settlementRadius = 96;

	public static final class Good {
		public String item = "minecraft:bread";
		public double base = 4.0;
		public double min = 1.0;
		public double max = 40.0;
		public int targetStock = 64;
		public double elasticity = 1.0;
		public boolean sellable = true;
	}

	public static final Map<String, Good> goods = new LinkedHashMap<>();
	public static final Map<String, Integer> salaries = new LinkedHashMap<>();

	private static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve("rl2dop.json");
	}

	public static void load() {
		resetDefaults();
		Path path = configPath();
		try {
			if (!Files.exists(path)) {
				save();
				Rl2dop.LOGGER.info("[rl2dop] создан конфиг {}", path);
				return;
			}
			String json = Files.readString(path, StandardCharsets.UTF_8);
			JsonObject root = JsonParser.parseString(json).getAsJsonObject();

			mintLimit = intOr(root, "mintLimit", mintLimit);
			startingCoins = intOr(root, "startingCoins", startingCoins);
			buySpread = doubleOr(root, "buySpread", buySpread);
			sellSpread = doubleOr(root, "sellSpread", sellSpread);
			shopRange = doubleOr(root, "shopRange", shopRange);
			tradeCooldownMs = intOr(root, "tradeCooldownMs", tradeCooldownMs);
			marketIntervalTicks = intOr(root, "marketIntervalTicks", marketIntervalTicks);
			priceSmoothing = doubleOr(root, "priceSmoothing", priceSmoothing);
			demandDecayPerCycle = intOr(root, "demandDecayPerCycle", demandDecayPerCycle);
			reputationDecayTicks = intOr(root, "reputationDecayTicks", reputationDecayTicks);
			brainIntervalTicks = intOr(root, "brainIntervalTicks", brainIntervalTicks);
			salaryPeriodTicks = intOr(root, "salaryPeriodTicks", salaryPeriodTicks);
			autoProfessionCooldownTicks = intOr(root, "autoProfessionCooldownTicks", autoProfessionCooldownTicks);
			autoProfessionGain = doubleOr(root, "autoProfessionGain", autoProfessionGain);
			maxNpcsPerSettlement = intOr(root, "maxNpcsPerSettlement", maxNpcsPerSettlement);
			displayRefreshTicks = intOr(root, "displayRefreshTicks", displayRefreshTicks);
			evilReputation = intOr(root, "evilReputation", evilReputation);
			shopRefuseReputation = intOr(root, "shopRefuseReputation", shopRefuseReputation);
			npcHitReputationPenalty = intOr(root, "npcHitReputationPenalty", npcHitReputationPenalty);
			defaultSalary = intOr(root, "defaultSalary", defaultSalary);
			settlementRadius = intOr(root, "settlementRadius", settlementRadius);

			if (root.has("goods") && root.get("goods").isJsonArray()) {
				JsonArray array = root.getAsJsonArray("goods");
				Map<String, Good> parsed = new LinkedHashMap<>();
				for (JsonElement element : array) {
					if (!element.isJsonObject()) {
						continue;
					}
					JsonObject o = element.getAsJsonObject();
					Good good = new Good();
					good.item = stringOr(o, "item", good.item);
					good.base = doubleOr(o, "base", good.base);
					good.min = doubleOr(o, "min", good.min);
					good.max = doubleOr(o, "max", good.max);
					good.targetStock = intOr(o, "targetStock", good.targetStock);
					good.elasticity = doubleOr(o, "elasticity", good.elasticity);
					good.sellable = boolOr(o, "sellable", good.sellable);
					parsed.put(good.item, good);
				}
				if (!parsed.isEmpty()) {
					goods.clear();
					goods.putAll(parsed);
				}
			}

			if (root.has("salaries") && root.get("salaries").isJsonObject()) {
				JsonObject s = root.getAsJsonObject("salaries");
				for (String key : s.keySet()) {
					salaries.put(key, intOr(s, key, defaultSalary));
				}
			}
			Rl2dop.LOGGER.info("[rl2dop] конфиг загружен: {} товаров, {} зарплат", goods.size(), salaries.size());
		} catch (Exception e) {
			Rl2dop.LOGGER.error("[rl2dop] не удалось прочитать конфиг, используются значения по умолчанию", e);
		}
	}

	private static void resetDefaults() {
		goods.clear();
		addGood("minecraft:bread", 4.0, 1.0, 40.0, 64, 1.0);
		addGood("minecraft:wheat", 2.0, 1.0, 20.0, 96, 1.1);
		addGood("minecraft:carrot", 2.0, 1.0, 20.0, 96, 1.1);
		addGood("minecraft:oak_log", 3.0, 1.0, 24.0, 96, 1.2);
		addGood("minecraft:iron_ingot", 12.0, 4.0, 90.0, 48, 1.3);
		addGood("minecraft:cobblestone", 1.5, 1.0, 12.0, 128, 1.4);
		addGood("minecraft:leather", 5.0, 2.0, 45.0, 48, 1.1);
		addGood("minecraft:white_wool", 4.0, 1.0, 30.0, 48, 1.1);
		addGood("minecraft:stick", 1.5, 1.0, 10.0, 64, 1.2);
		addGood("minecraft:stone_sword", 18.0, 6.0, 120.0, 8, 1.0);
		addGood(MOD_BANDAGE, 6.0, 2.0, 40.0, 24, 0.9);
		addGood(MOD_MEDKIT, 14.0, 5.0, 80.0, 12, 0.9);

		salaries.clear();
		salaries.put("farmer", 10);
		salaries.put("trader", 14);
		salaries.put("guard", 16);
		salaries.put("lumberjack", 11);
		salaries.put("cook", 12);
		salaries.put("miner", 13);
		salaries.put("rancher", 10);
		salaries.put("craftsman", 15);
		salaries.put("builder", 14);
		salaries.put("healer", 15);
	}

	public static final String MOD_BANDAGE = "rl2dop:bandage";
	public static final String MOD_MEDKIT = "rl2dop:medkit";

	private static void addGood(String item, double base, double min, double max, int targetStock, double elasticity) {
		Good good = new Good();
		good.item = item;
		good.base = base;
		good.min = min;
		good.max = max;
		good.targetStock = targetStock;
		good.elasticity = elasticity;
		goods.put(item, good);
	}

	public static Good good(String itemId) {
		return goods.get(itemId);
	}

	public static List<String> goodIds() {
		return new ArrayList<>(goods.keySet());
	}

	public static int salary(String profession) {
		return salaries.getOrDefault(profession, defaultSalary);
	}

	public static void save() {
		try {
			JsonObject root = new JsonObject();
			root.addProperty("mintLimit", mintLimit);
			root.addProperty("startingCoins", startingCoins);
			root.addProperty("buySpread", buySpread);
			root.addProperty("sellSpread", sellSpread);
			root.addProperty("shopRange", shopRange);
			root.addProperty("tradeCooldownMs", tradeCooldownMs);
			root.addProperty("marketIntervalTicks", marketIntervalTicks);
			root.addProperty("priceSmoothing", priceSmoothing);
			root.addProperty("demandDecayPerCycle", demandDecayPerCycle);
			root.addProperty("reputationDecayTicks", reputationDecayTicks);
			root.addProperty("brainIntervalTicks", brainIntervalTicks);
			root.addProperty("salaryPeriodTicks", salaryPeriodTicks);
			root.addProperty("autoProfessionCooldownTicks", autoProfessionCooldownTicks);
			root.addProperty("autoProfessionGain", autoProfessionGain);
			root.addProperty("maxNpcsPerSettlement", maxNpcsPerSettlement);
			root.addProperty("displayRefreshTicks", displayRefreshTicks);
			root.addProperty("evilReputation", evilReputation);
			root.addProperty("shopRefuseReputation", shopRefuseReputation);
			root.addProperty("npcHitReputationPenalty", npcHitReputationPenalty);
			root.addProperty("defaultSalary", defaultSalary);
			root.addProperty("settlementRadius", settlementRadius);

			JsonArray array = new JsonArray();
			for (Good good : goods.values()) {
				JsonObject o = new JsonObject();
				o.addProperty("item", good.item);
				o.addProperty("base", good.base);
				o.addProperty("min", good.min);
				o.addProperty("max", good.max);
				o.addProperty("targetStock", good.targetStock);
				o.addProperty("elasticity", good.elasticity);
				o.addProperty("sellable", good.sellable);
				array.add(o);
			}
			root.add("goods", array);

			JsonObject salariesObject = new JsonObject();
			salaries.forEach(salariesObject::addProperty);
			root.add("salaries", salariesObject);

			Path path = configPath();
			Files.createDirectories(path.getParent());
			Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
		} catch (Exception e) {
			Rl2dop.LOGGER.error("[rl2dop] не удалось сохранить конфиг", e);
		}
	}

	private static int intOr(JsonObject o, String key, int fallback) {
		try {
			return o.has(key) ? o.get(key).getAsInt() : fallback;
		} catch (Exception e) {
			return fallback;
		}
	}

	private static double doubleOr(JsonObject o, String key, double fallback) {
		try {
			return o.has(key) ? o.get(key).getAsDouble() : fallback;
		} catch (Exception e) {
			return fallback;
		}
	}

	private static String stringOr(JsonObject o, String key, String fallback) {
		try {
			return o.has(key) ? o.get(key).getAsString() : fallback;
		} catch (Exception e) {
			return fallback;
		}
	}

	private static boolean boolOr(JsonObject o, String key, boolean fallback) {
		try {
			return o.has(key) ? o.get(key).getAsBoolean() : fallback;
		} catch (Exception e) {
			return fallback;
		}
	}
}
