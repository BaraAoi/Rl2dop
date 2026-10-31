package com.baraaoi.rl2dop.economy;

import com.baraaoi.rl2dop.Rl2dopConfig;
import com.baraaoi.rl2dop.entity.Profession;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Данные поселения: казна, склад, спрос, зарплаты, цены, список NPC, репутация игроков, лимит монет.
 * Всё это живёт только на сервере.
 */
public class Settlement {
	public static final int MAX_STOCK = 4096;

	public String id;
	public String name;
	public String dimension = "minecraft:overworld";
	public long hallPos;
	public long boardPos = Long.MIN_VALUE;
	public long shopPos = Long.MIN_VALUE;
	public long treasury;
	public int coinLimit = Rl2dopConfig.mintLimit;
	public int buildProgress;

	public final Map<String, Integer> stock = new LinkedHashMap<>();
	public final Map<String, Integer> warehouse = new LinkedHashMap<>();
	public final Map<String, Double> prices = new LinkedHashMap<>();
	public final Map<String, Integer> demand = new LinkedHashMap<>();
	public final Map<String, Integer> salaries = new LinkedHashMap<>();
	public final Map<String, Integer> jobs = new LinkedHashMap<>();
	public final Map<String, Integer> reputation = new LinkedHashMap<>();
	public final List<String> npcs = new ArrayList<>();
	public final List<String> shelves = new ArrayList<>();

	public Settlement(String id, String name) {
		this.id = id;
		this.name = name;
	}

	public static Settlement create(String id, String name, BlockPos hall, String dimension) {
		Settlement settlement = new Settlement(id, name);
		settlement.hallPos = hall.asLong();
		settlement.dimension = dimension;
		settlement.treasury = 400L;
		settlement.coinLimit = Rl2dopConfig.mintLimit;
		for (Rl2dopConfig.Good good : Rl2dopConfig.goods.values()) {
			settlement.stock.put(good.item, Math.max(8, good.targetStock / 2));
			settlement.prices.put(good.item, good.base);
			settlement.demand.put(good.item, 0);
		}
		for (Profession profession : Profession.values()) {
			settlement.salaries.put(profession.id(), Rl2dopConfig.salary(profession.id()));
		}
		return settlement;
	}

	public BlockPos hall() {
		return BlockPos.of(hallPos);
	}

	/** Точка входа магазина: явный маркер игрока или угол ратуши. */
	public BlockPos shop() {
		return shopPos != Long.MIN_VALUE ? BlockPos.of(shopPos) : hall().offset(2, 0, 2);
	}

	public void setShop(BlockPos pos) {
		shopPos = pos.asLong();
	}

	public int jobCount(Profession profession) {
		return jobs.getOrDefault(profession.id(), 0);
	}

	public void adjustJob(Profession profession, int delta) {
		jobs.put(profession.id(), Math.max(0, jobCount(profession) + delta));
	}

	public int salaryFor(Profession profession) {
		return salaries.getOrDefault(profession.id(), Rl2dopConfig.salary(profession.id()));
	}

	public int stock(String item) {
		return stock.getOrDefault(item, 0);
	}

	public void addStock(String item, int amount) {
		stock.put(item, clampStock(stock(item) + amount));
	}

	public boolean takeStock(String item, int amount) {
		int current = stock(item);
		if (current < amount) {
			return false;
		}
		stock.put(item, current - amount);
		return true;
	}

	public int warehouse(String item) {
		return warehouse.getOrDefault(item, 0);
	}

	public void addWarehouse(String item, int amount) {
		warehouse.put(item, clampStock(warehouse(item) + amount));
	}

	public boolean takeWarehouse(String item, int amount) {
		int current = warehouse(item);
		if (current < amount) {
			return false;
		}
		warehouse.put(item, current - amount);
		return true;
	}

	public double price(String item) {
		return prices.getOrDefault(item, 1.0D);
	}

	public int demand(String item) {
		return demand.getOrDefault(item, 0);
	}

	public void addDemand(String item, int amount) {
		demand.put(item, Math.max(0, Math.min(150, demand(item) + amount)));
	}

	public int reputation(UUID player) {
		return reputation.getOrDefault(player.toString(), 0);
	}

	public int reputation(String uuid) {
		return reputation.getOrDefault(uuid, 0);
	}

	public void setReputation(UUID player, int value) {
		reputation.put(player.toString(), Math.max(-100, Math.min(100, value)));
	}

	public void addReputation(UUID player, int delta) {
		setReputation(player, reputation(player) + delta);
	}

	public boolean isNpcRegistered(UUID uuid) {
		return npcs.contains(uuid.toString());
	}

	public boolean registerNpc(UUID uuid) {
		if (npcs.size() >= Rl2dopConfig.maxNpcsPerSettlement) {
			return false;
		}
		if (!npcs.contains(uuid.toString())) {
			npcs.add(uuid.toString());
		}
		return true;
	}

	public void unregisterNpc(UUID uuid) {
		npcs.remove(uuid.toString());
	}

	public boolean near(BlockPos pos, double radius) {
		BlockPos hall = hall();
		return hall.distSqr(pos) <= (long) (radius * radius);
	}

	public String displayName() {
		return name == null || name.isBlank() ? id : name;
	}

	private static int clampStock(int value) {
		return Math.max(0, Math.min(MAX_STOCK, value));
	}

	// ---- NBT ----

	public CompoundTag save(CompoundTag tag) {
		tag.putString("Id", id);
		tag.putString("Name", name == null ? id : name);
		tag.putString("Dim", dimension);
		tag.putLong("Hall", hallPos);
		tag.putLong("Board", boardPos);
		tag.putLong("ShopPos", shopPos);
		tag.putLong("Treasury", treasury);
		tag.putInt("CoinLimit", coinLimit);
		tag.putInt("Build", buildProgress);
		tag.put("Stock", intMap(stock));
		tag.put("Warehouse", intMap(warehouse));
		tag.put("Demand", intMap(demand));
		tag.put("Salaries", intMap(salaries));
		tag.put("Jobs", intMap(jobs));
		tag.put("Reputation", intMap(reputation));
		tag.put("Prices", doubleMap(prices));
		tag.put("Npcs", stringList(npcs));
		tag.put("Shelves", stringList(shelves));
		return tag;
	}

	public static Settlement load(CompoundTag tag) {
		String id = tag.getString("Id");
		Settlement settlement = new Settlement(id, id);
		settlement.name = tag.getString("Name");
		settlement.dimension = tag.contains("Dim") ? tag.getString("Dim") : "minecraft:overworld";
		settlement.hallPos = tag.getLong("Hall");
		settlement.boardPos = tag.contains("Board") ? tag.getLong("Board") : Long.MIN_VALUE;
		settlement.shopPos = tag.contains("ShopPos") ? tag.getLong("ShopPos") : Long.MIN_VALUE;
		settlement.treasury = tag.getLong("Treasury");
		settlement.coinLimit = tag.contains("CoinLimit") ? tag.getInt("CoinLimit") : Rl2dopConfig.mintLimit;
		settlement.buildProgress = tag.getInt("Build");
		readIntMap(tag.getList("Stock", 10), settlement.stock);
		readIntMap(tag.getList("Warehouse", 10), settlement.warehouse);
		readIntMap(tag.getList("Demand", 10), settlement.demand);
		readIntMap(tag.getList("Salaries", 10), settlement.salaries);
		readIntMap(tag.getList("Jobs", 10), settlement.jobs);
		readIntMap(tag.getList("Reputation", 10), settlement.reputation);
		readDoubleMap(tag.getList("Prices", 10), settlement.prices);
		readStringList(tag.getList("Npcs", 8), settlement.npcs);
		readStringList(tag.getList("Shelves", 8), settlement.shelves);
		// если конфиг поменялся — добить недостающие товары
		for (Rl2dopConfig.Good good : Rl2dopConfig.goods.values()) {
			settlement.stock.putIfAbsent(good.item, Math.max(8, good.targetStock / 2));
			settlement.prices.putIfAbsent(good.item, good.base);
			settlement.demand.putIfAbsent(good.item, 0);
		}
		for (Profession profession : Profession.values()) {
			settlement.salaries.putIfAbsent(profession.id(), Rl2dopConfig.salary(profession.id()));
		}
		return settlement;
	}

	private static CompoundTag intMap(Map<String, Integer> map) {
		CompoundTag tag = new CompoundTag();
		map.forEach((key, value) -> tag.putInt(key, value == null ? 0 : value));
		return tag;
	}

	private static CompoundTag doubleMap(Map<String, Double> map) {
		CompoundTag tag = new CompoundTag();
		map.forEach((key, value) -> tag.putDouble(key, value == null ? 0.0D : value));
		return tag;
	}

	private static ListTag stringList(List<String> values) {
		ListTag list = new ListTag();
		for (String value : values) {
			CompoundTag entry = new CompoundTag();
			entry.putString("V", value);
			list.add(entry);
		}
		return list;
	}

	private static void readIntMap(ListTag list, Map<String, Integer> target) {
		for (int i = 0; i < list.size(); i++) {
			CompoundTag entry = list.getCompound(i);
			for (String key : new ArrayList<>(entry.getAllKeys())) {
				target.put(key, entry.getInt(key));
			}
		}
	}

	private static void readDoubleMap(ListTag list, Map<String, Double> target) {
		for (int i = 0; i < list.size(); i++) {
			CompoundTag entry = list.getCompound(i);
			for (String key : new ArrayList<>(entry.getAllKeys())) {
				target.put(key, entry.getDouble(key));
			}
		}
	}

	private static void readStringList(ListTag list, List<String> target) {
		for (int i = 0; i < list.size(); i++) {
			CompoundTag entry = list.getCompound(i);
			target.add(entry.getString("V"));
		}
	}

	public static String slug(String input) {
		String cleaned = input == null ? "settlement" : input.toLowerCase(Locale.ROOT).trim()
				.replace(' ', '_')
				.replaceAll("[^a-z0-9_\\-]", "");
		return cleaned.isEmpty() ? "settlement" : cleaned;
	}
}
