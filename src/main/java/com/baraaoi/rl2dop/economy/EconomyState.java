package com.baraaoi.rl2dop.economy;

import com.baraaoi.rl2dop.Rl2dop;
import com.baraaoi.rl2dop.Rl2dopConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Корневое сохранение мода: эмиссия монет, поселения, журнал экономики.
 * Хранится в {@code world/data/rl2dop_economy.dat} (PersistentState / SavedData).
 */
public class EconomyState extends SavedData {
	public static final String FILE_ID = "rl2dop_economy";
	public static final int MAX_LOG = 300;

	public long minted;
	public String defaultSettlementId = "";
	public final Map<String, Settlement> settlements = new LinkedHashMap<>();
	public final List<String> log = new ArrayList<>();

	// не сохраняется: служебные таймеры тикера
	public transient long lastMarketTick = -1L;
	public transient long lastReputationTick = -1L;

	public static EconomyState get(MinecraftServer server) {
		ServerLevel overworld = server.overworld();
		return overworld.getDataStorage().computeIfAbsent(
				new SavedData.Factory<>(EconomyState::new, EconomyState::load), FILE_ID);
	}

	public Settlement settlement(String id) {
		return id == null ? null : settlements.get(id);
	}

	public Collection<Settlement> all() {
		return settlements.values();
	}

	public Settlement defaultSettlement() {
		Settlement settlement = settlements.get(defaultSettlementId);
		if (settlement == null && !settlements.isEmpty()) {
			settlement = settlements.values().iterator().next();
			defaultSettlementId = settlement.id;
		}
		return settlement;
	}

	public Settlement createSettlement(String name, BlockPos hall, String dimension) {
		String baseId = Settlement.slug(name);
		String id = baseId;
		int suffix = 2;
		while (settlements.containsKey(id)) {
			id = baseId + "_" + suffix++;
		}
		Settlement settlement = Settlement.create(id, name, hall, dimension);
		settlements.put(id, settlement);
		if (defaultSettlementId.isEmpty()) {
			defaultSettlementId = id;
		}
		setDirty();
		log("поселение " + settlement.displayName() + " (" + id + ") основано в " + hall.toShortString()
				+ " [" + dimension + "]");
		return settlement;
	}

	public long mint(long requested) {
		long allowed = Math.min(Math.max(0, requested), Math.max(0, Rl2dopConfig.mintLimit - minted));
		minted += allowed;
		if (allowed > 0) {
			setDirty();
		}
		return allowed;
	}

	public void log(String message) {
		log.add(message);
		while (log.size() > MAX_LOG) {
			log.remove(0);
		}
		setDirty();
	}

	public void reset() {
		minted = 0;
		settlements.clear();
		log.clear();
		defaultSettlementId = "";
		lastMarketTick = -1L;
		lastReputationTick = -1L;
		setDirty();
	}

	/** Краткая сводка для команды market_dump / HUD. */
	public List<String> summary() {
		List<String> lines = new ArrayList<>();
		lines.add("Эмиссия: " + minted + " / " + Rl2dopConfig.mintLimit + " монет");
		if (settlements.isEmpty()) {
			lines.add("Поселений нет. Создай: /settle_spawn <имя>");
			return lines;
		}
		for (Settlement settlement : settlements.values()) {
			lines.add("— " + settlement.displayName() + " (" + settlement.id + ") казна=" + settlement.treasury
					+ " NPC=" + settlement.npcs.size() + " постройка=" + settlement.buildProgress + "%");
			for (Rl2dopConfig.Good good : Rl2dopConfig.goods.values()) {
				lines.add(String.format(Locale.ROOT, "   %s: сток=%d цена=%.1f спрос=%d",
						shortName(good.item), settlement.stock(good.item), settlement.price(good.item),
						settlement.demand(good.item)));
			}
		}
		return lines;
	}

	public static String shortName(String itemId) {
		int index = itemId.indexOf(':');
		return index >= 0 ? itemId.substring(index + 1) : itemId;
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		tag.putLong("Minted", minted);
		tag.putString("Default", defaultSettlementId == null ? "" : defaultSettlementId);
		ListTag settlementsTag = new ListTag();
		for (Settlement settlement : settlements.values()) {
			settlementsTag.add(settlement.save(new CompoundTag()));
		}
		tag.put("Settlements", settlementsTag);
		ListTag logTag = new ListTag();
		for (String line : log) {
			CompoundTag entry = new CompoundTag();
			entry.putString("V", line);
			logTag.add(entry);
		}
		tag.put("Log", logTag);
		return tag;
	}

	public static EconomyState load(CompoundTag tag, HolderLookup.Provider registries) {
		EconomyState state = new EconomyState();
		state.minted = tag.getLong("Minted");
		state.defaultSettlementId = tag.getString("Default");
		ListTag settlementsTag = tag.getList("Settlements", 10);
		for (int i = 0; i < settlementsTag.size(); i++) {
			Settlement settlement = Settlement.load(settlementsTag.getCompound(i));
			state.settlements.put(settlement.id, settlement);
		}
		ListTag logTag = tag.getList("Log", 10);
		for (int i = 0; i < logTag.size(); i++) {
			state.log.add(logTag.getCompound(i).getString("V"));
		}
		Rl2dop.LOGGER.info("[rl2dop] сохранение загружено: {} поселений, эмиссия {}", state.settlements.size(), state.minted);
		return state;
	}

	/** Случайные имена для NPC, чтобы не плодить "Citizen". */
	private static final String[] NAMES = {
			"Ганс", "Марта", "Ивар", "Лина", "Отто", "Бруно", "Аста", "Клаус", "Мира", "Дитрих",
			"Зельда", "Фриц", "Хельга", "Йорн", "Тиль", "Грета", "Пауль", "Анна", "Курт", "Ульрих"
	};

	public static String randomName(RandomSource random) {
		return NAMES[random.nextInt(NAMES.length)];
	}
}
