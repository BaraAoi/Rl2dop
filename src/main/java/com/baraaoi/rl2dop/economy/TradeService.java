package com.baraaoi.rl2dop.economy;

import com.baraaoi.rl2dop.Rl2dopConfig;
import com.baraaoi.rl2dop.entity.CitizenEntity;
import com.baraaoi.rl2dop.net.ModPackets;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Вся торговля считается здесь, на сервере. Клиент только присылает "что хочу",
 * сервер каждый раз заново проверяет монеты, предметы, сток, казну и дистанцию.
 * Это и есть защита от дюпа через экран.
 */
public final class TradeService {
	private TradeService() {
	}

	public static final int ACTION_BUY = 0;
	public static final int ACTION_SELL = 1;
	public static final int ACTION_HAGGLE = 2;

	private static final Map<UUID, Long> LAST_ACTION = new HashMap<>();
	private static final Map<UUID, Long> HAGGLE_UNTIL = new HashMap<>();
	private static final Map<UUID, Double> HAGGLE_FACTOR = new HashMap<>();

	public static void clearPlayer(UUID uuid) {
		LAST_ACTION.remove(uuid);
		HAGGLE_UNTIL.remove(uuid);
		HAGGLE_FACTOR.remove(uuid);
	}

	public static double haggleFactor(ServerPlayer player) {
		Long until = HAGGLE_UNTIL.get(player.getUUID());
		if (until == null || until < System.currentTimeMillis()) {
			return 1.0D;
		}
		return HAGGLE_FACTOR.getOrDefault(player.getUUID(), 1.0D);
	}

	public static void handle(ServerPlayer player, int citizenId, String itemId, int amount, int action) {
		if (!checkCooldown(player)) {
			return;
		}
		ServerLevel level = player.serverLevel();
		Settlement settlement;
		CitizenEntity citizen = null;

		if (citizenId == ShopService.HALL_ID) {
			settlement = SettlementService.forPlayer(player);
			if (settlement == null) {
				message(player, "§cРатуша не найдена.");
				return;
			}
			double hallRange = Rl2dopConfig.shopRange * 2.0D;
			if (settlement.shop().distSqr(player.blockPosition()) > hallRange * hallRange) {
				message(player, "§cСлишком далеко от ратуши.");
				return;
			}
		} else {
			Entity entity = level.getEntity(citizenId);
			if (!(entity instanceof CitizenEntity found)) {
				return;
			}
			citizen = found;
			settlement = SettlementService.byId(level, citizen.getSettlementId());
			if (settlement == null) {
				return;
			}
			double range = Rl2dopConfig.shopRange;
			if (player.distanceToSqr(citizen) > range * range) {
				message(player, "§cТорговец слишком далеко.");
				return;
			}
		}

		boolean mayor = citizen != null && citizen.isMayor();

		if (action == ACTION_HAGGLE) {
			if (mayor) {
				message(player, "§e" + citizen.getCitizenName()
						+ "§7: «Со мной торговаться незачем — я беру всё по заготовленной цене.»");
				refresh(player, citizen, settlement);
				return;
			}
			haggle(player, settlement, itemId);
			refresh(player, citizen, settlement);
			return;
		}

		if (amount < 1 || amount > 64) {
			return;
		}
		Rl2dopConfig.Good good = Rl2dopConfig.good(itemId);
		if (good == null) {
			message(player, "§cТакой товар здесь не торгуется.");
			return;
		}
		int reputation = settlement.reputation(player.getUUID());
		double haggle = haggleFactor(player);

		boolean institution = citizen == null || mayor;
		boolean ok;
		if (action == ACTION_BUY) {
			if (mayor) {
				message(player, "§e" + citizen.getCitizenName()
						+ "§7: «Я у табличек стою, а не торгую. За товаром — в лавку.»");
				refresh(player, citizen, settlement);
				return;
			}
			ok = buy(player, settlement, good, amount, reputation, haggle);
		} else {
			ok = sell(player, settlement, good, amount, reputation, haggle, institution);
		}

		refresh(player, citizen, settlement);
		ModPackets.syncTo(player);
		// инвентарь менялся на сервере — принудительно синхронизируем слоты клиента
		Money.syncInventory(player);
		if (!ok) {
			return;
		}
		EconomyState state = EconomyState.get(level.getServer());
		state.log((action == ACTION_BUY ? "покупка " : "продажа ") + amount + "x " + good.item
				+ " игроком " + player.getGameProfile().getName()
				+ " в " + settlement.id
				+ (mayor ? " (глава, без торга)" : (citizen == null ? " (ратуша)" : "")));
		state.setDirty();
	}

	private static boolean buy(ServerPlayer player, Settlement settlement, Rl2dopConfig.Good good, int amount,
							   int reputation, double haggle) {
		int price = MarketMath.buyPrice(settlement, good, reputation, haggle) * amount;
		int stock = settlement.stock(good.item);
		if (stock < amount) {
			message(player, "§cНа складе только " + stock + " шт.");
			return false;
		}
		if (Money.total(player) < price) {
			message(player, "§cНужно " + price + "м, у тебя " + Money.total(player) + "м.");
			return false;
		}
		if (!Money.take(player, price)) {
			return false;
		}
		settlement.takeStock(good.item, amount);
		settlement.treasury += price;
		settlement.addDemand(good.item, Math.max(1, amount / 2));
		ItemStack stack = new ItemStack(resolveItem(good.item), amount);
		if (!player.getInventory().add(stack)) {
			player.drop(stack, false);
		}
		message(player, "§aКуплено §f" + amount + "x " + stack.getHoverName().getString() + " §aза §f" + price + "м");
		EconomyState.get(player.serverLevel().getServer()).setDirty();
		return true;
	}

	private static boolean sell(ServerPlayer player, Settlement settlement, Rl2dopConfig.Good good, int amount,
								int reputation, double haggle, boolean hall) {
		if (!good.sellable) {
			message(player, "§cЭтот товар не принимают.");
			return false;
		}
		Item item = resolveItem(good.item);
		if (item == Items.AIR) {
			return false;
		}
		if (InventoryUtil.countItems(player, item) < amount) {
			message(player, "§cУ тебя нет столько " + new ItemStack(item).getHoverName().getString() + ".");
			return false;
		}
		int payout = MarketMath.sellPrice(settlement, good, reputation, haggle) * amount;
		if (institution) {
			payout = (int) Math.ceil(payout * Math.max(1.0D, Rl2dopConfig.mayorBuyBonus));
		}
		if (settlement.treasury < payout) {
			message(player, "§cВ казне не хватает монет (" + settlement.treasury + "м). Продай позже.");
			return false;
		}
		if (!InventoryUtil.takeItems(player, item, amount)) {
			return false;
		}
		settlement.treasury -= payout;
		settlement.addStock(good.item, amount);
		settlement.addDemand(good.item, -Math.max(1, amount));
		Money.give(player, payout);
		message(player, "§aПродано §f" + amount + "x " + new ItemStack(item).getHoverName().getString()
				+ " §aза §f" + payout + "м");
		EconomyState.get(player.serverLevel().getServer()).setDirty();
		return true;
	}

	private static void haggle(ServerPlayer player, Settlement settlement, String itemId) {
		UUID uuid = player.getUUID();
		long now = System.currentTimeMillis();
		Long until = HAGGLE_UNTIL.get(uuid);
		if (until != null && until > now) {
			message(player, "§7Торговец уже назвал тебе лучшую цену.");
			return;
		}
		Rl2dopConfig.Good good = Rl2dopConfig.good(itemId);
		int reputation = settlement.reputation(uuid);
		int demand = good == null ? 0 : settlement.demand(good.item);
		// чем нужнее товар и чем лучше репутация — тем охотнее уступают
		double chance = 0.35D + reputation / 300.0D + (good == null ? 0 : settlement.stock(good.item) / 256.0D);
		boolean success = player.getRandom().nextDouble() < Math.max(0.05D, Math.min(0.95D, chance));
		if (success) {
			double factor = 0.92D;
			HAGGLE_FACTOR.put(uuid, factor);
			HAGGLE_UNTIL.put(uuid, now + 120_000L);
			message(player, "§aТорг удался: скидка 8% на 2 минуты. §7(спрос " + demand + ")");
		} else {
			HAGGLE_UNTIL.put(uuid, now + 60_000L);
			HAGGLE_FACTOR.put(uuid, 1.0D);
			message(player, "§cТорговец отказал: «Цена и так честная.»");
		}
	}

	private static void refresh(ServerPlayer player, CitizenEntity citizen, Settlement settlement) {
		int reputation = settlement.reputation(player.getUUID());
		if (citizen != null) {
			double bonus = citizen.isMayor() ? Math.max(1.0D, Rl2dopConfig.mayorBuyBonus) : 1.0D;
			ModPackets.sendShop(player, citizen.getId(),
					(citizen.isMayor() ? "Глава " : "") + citizen.getCitizenName() + " · " + settlement.displayName(),
					ShopService.feed(player, settlement, reputation, haggleFactor(player), bonus));
		} else {
			ModPackets.sendShop(player, ShopService.HALL_ID, "Ратуша · " + settlement.displayName(),
					ShopService.feed(player, settlement, reputation, haggleFactor(player),
							Math.max(1.0D, Rl2dopConfig.mayorBuyBonus)));
		}
	}

	private static boolean checkCooldown(ServerPlayer player) {
		long now = System.currentTimeMillis();
		Long last = LAST_ACTION.get(player.getUUID());
		if (last != null && now - last < Rl2dopConfig.tradeCooldownMs) {
			return false;
		}
		LAST_ACTION.put(player.getUUID(), now);
		return true;
	}

	public static Item resolveItem(String itemId) {
		ResourceLocation id = ResourceLocation.tryParse(itemId);
		if (id == null) {
			return Items.AIR;
		}
		Item item = BuiltInRegistries.ITEM.get(id);
		return item == null ? Items.AIR : item;
	}

	public static void message(ServerPlayer player, String text) {
		player.displayClientMessage(Component.literal(text).withStyle(ChatFormatting.WHITE), false);
	}
}
