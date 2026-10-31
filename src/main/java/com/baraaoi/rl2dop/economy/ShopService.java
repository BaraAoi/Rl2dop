package com.baraaoi.rl2dop.economy;

import com.baraaoi.rl2dop.Rl2dopConfig;
import com.baraaoi.rl2dop.entity.CitizenEntity;
import com.baraaoi.rl2dop.entity.Profession;
import com.baraaoi.rl2dop.net.ModPackets;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Торговый экран: сервер собирает ленту товаров с ценами, клиент её только рисует. */
public final class ShopService {
	private ShopService() {
	}

	public static final int HALL_ID = -1;

	/** Открыть окно главы поселения: скупает всё, торг не нужен. */
	public static void openMayor(ServerPlayer player, CitizenEntity citizen, Settlement settlement) {
		player.displayClientMessage(Component.literal("§6" + citizen.getCitizenName()
				+ " §7(глава)§f: «Неси всё, что накопал и напёк. Торг не нужен — цена и так честная.»"), false);
		ModPackets.sendShop(player, citizen.getId(), "Глава " + citizen.getCitizenName() + " · " + settlement.displayName(),
				ShopService.feed(player, settlement, settlement.reputation(player.getUUID()), 1.0D,
						Math.max(1.0D, com.baraaoi.rl2dop.Rl2dopConfig.mayorBuyBonus)));
	}

	public static void open(ServerPlayer player, CitizenEntity citizen) {
		Settlement settlement = SettlementService.byId(player.serverLevel(), citizen.getSettlementId());
		if (settlement == null) {
			player.displayClientMessage(Component.literal("§cУ этого NPC нет поселения."), false);
			return;
		}
		int reputation = settlement.reputation(player.getUUID());
		if (reputation <= Rl2dopConfig.shopRefuseReputation && citizen.getProfession() != Profession.GUARD) {
			player.displayClientMessage(Component.literal("§c" + citizen.getCitizenName()
					+ ": «Я не торгую с теми, кто грабит наших.»"), false);
			return;
		}
		player.displayClientMessage(Component.literal("§e" + citizen.getCitizenName() + "§7: "
				+ greeting(reputation)), false);
		ModPackets.sendShop(player, citizen.getId(), citizen.getCitizenName() + " · " + settlement.displayName(),
				feed(player, settlement, reputation, TradeService.haggleFactor(player)));
	}

	public static void openHall(ServerPlayer player, Settlement settlement) {
		int reputation = settlement.reputation(player.getUUID());
		player.displayClientMessage(Component.literal("§6Ратуша §7" + settlement.displayName()
				+ "§7: казна §f" + settlement.treasury + "м§7. Продавай нужные товары прямо сюда."), false);
		ModPackets.sendShop(player, HALL_ID, "Ратуша · " + settlement.displayName(),
				feed(player, settlement, reputation, TradeService.haggleFactor(player)));
	}

	/** Строки вида {@code itemId|buy|sell|stock|demand|held} — клиент разбирает их сам. */
	public static List<String> feed(ServerPlayer player, Settlement settlement, int reputation, double haggle) {
		return feed(player, settlement, reputation, haggle, 1.0D);
	}

	/** {@code sellBonus} — надбавка скупщика (ратуша и глава поселения платят больше). */
	public static List<String> feed(ServerPlayer player, Settlement settlement, int reputation, double haggle,
			double sellBonus) {
		List<String> rows = new ArrayList<>();
		for (Rl2dopConfig.Good good : Rl2dopConfig.goods.values()) {
			int buy = MarketMath.buyPrice(settlement, good, reputation, haggle);
			int sell = (int) Math.ceil(MarketMath.sellPrice(settlement, good, reputation, haggle)
					* Math.max(1.0D, sellBonus));
			int held = InventoryUtil.countItems(player, TradeService.resolveItem(good.item));
			rows.add(good.item + "|" + buy + "|" + sell + "|" + settlement.stock(good.item) + "|"
					+ settlement.demand(good.item) + "|" + held);
		}
		return rows;
	}

	public static String greeting(int reputation) {
		if (reputation >= 40) {
			return "«Рад видеть тебя снова, друг! Смотри, что есть.»";
		}
		if (reputation >= 10) {
			return "«Заходи, у нас всё честно.»";
		}
		if (reputation <= -20) {
			return "«Смотрю на тебя и держу руку на кошеле.»";
		}
		return "«Что продаёшь, что покупаешь?»";
	}

	/** Строка для HUD: краткий обзор рынка. */
	public static String ticker(Settlement settlement) {
		if (settlement == null) {
			return "";
		}
		StringBuilder builder = new StringBuilder();
		for (Rl2dopConfig.Good good : Rl2dopConfig.goods.values()) {
			if (builder.length() > 0) {
				builder.append(" §8| ");
			}
			builder.append("§f").append(EconomyState.shortName(good.item))
					.append(" §7").append(String.format(Locale.ROOT, "%.1f", settlement.price(good.item)));
		}
		return builder.toString();
	}

	/** Текст-табло ратуши: что нужно поселению. */
	public static String boardText(Settlement settlement) {
		List<String> needed = new ArrayList<>();
		for (Rl2dopConfig.Good good : Rl2dopConfig.goods.values()) {
			int stock = settlement.stock(good.item);
			if (settlement.demand(good.item) > 0 || stock < Math.max(4, good.targetStock / 3)) {
				needed.add(EconomyState.shortName(good.item));
				if (needed.size() >= 4) {
					break;
				}
			}
		}
		String list = needed.isEmpty() ? "всё есть" : String.join(", ", needed);
		return "§6Ратуша §7· казна §f" + settlement.treasury + "м §7· нужно: §e" + list;
	}
}
