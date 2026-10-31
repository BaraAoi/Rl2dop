package com.baraaoi.rl2dop.command;

import com.baraaoi.rl2dop.Rl2dopConfig;
import com.baraaoi.rl2dop.economy.CoinUtil;
import com.baraaoi.rl2dop.economy.Displays;
import com.baraaoi.rl2dop.economy.EconomyState;
import com.baraaoi.rl2dop.economy.Settlement;
import com.baraaoi.rl2dop.economy.SettlementService;
import com.baraaoi.rl2dop.economy.ShopService;
import com.baraaoi.rl2dop.entity.CitizenEntity;
import com.baraaoi.rl2dop.entity.Profession;
import com.baraaoi.rl2dop.net.ModPackets;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/** Все команды отладки и администрирования экономики. */
public final class Rl2dopCommands {
	private Rl2dopCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			registerSpawnNpc(dispatcher);
			registerGiveCoins(dispatcher);
			registerSetReputation(dispatcher);
			registerSettleSpawn(dispatcher);
			registerSettleBind(dispatcher);
			registerMarketDump(dispatcher);
			registerResetEconomy(dispatcher);
			registerSalarySet(dispatcher);
			registerPriceSet(dispatcher);
			registerShopOpen(dispatcher);
			registerShelfAdd(dispatcher);
			registerHallBoard(dispatcher);
			registerNpcList(dispatcher);
			registerEconLog(dispatcher);
			registerRoot(dispatcher);
		});
	}

	// ---------- 0/2: NPC ----------

	private static void registerSpawnNpc(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("spawn_npc")
				.requires(source -> source.hasPermission(2))
				.then(Commands.argument("profession", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(Profession.ids(), builder))
						.executes(context -> spawnNpc(context, StringArgumentType.getString(context, "profession"), null))
						.then(Commands.argument("name", StringArgumentType.greedyString())
								.executes(context -> spawnNpc(context,
										StringArgumentType.getString(context, "profession"),
										StringArgumentType.getString(context, "name"))))));
	}

	private static int spawnNpc(CommandContext<CommandSourceStack> context, String professionId, String name)
			throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayerOrException();
		ServerLevel level = (ServerLevel) source.getLevel();
		Settlement settlement = SettlementService.forPlayer(player);
		if (settlement == null) {
			source.sendFailure(Component.literal("Нет поселения рядом. Создай: /settle_spawn <имя>"));
			return 0;
		}
		Profession profession = Profession.byId(professionId);
		CitizenEntity citizen = SettlementService.spawnCitizen(level, settlement, profession,
				player.blockPosition(), name);
		if (citizen == null) {
			source.sendFailure(Component.literal("Не удалось создать NPC."));
			return 0;
		}
		if (profession == Profession.GUARD) {
			citizen.setGuardVariant(level.random.nextBoolean() ? 1 : 0);
		}
		source.sendSuccess(() -> Component.literal("§aNPC §f" + citizen.getCitizenName() + " §a("
				+ profession.id() + ") в поселении " + settlement.displayName()), true);
		return 1;
	}

	// ---------- 1: деньги ----------

	private static void registerGiveCoins(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("give_coins")
				.requires(source -> source.hasPermission(2))
				.then(Commands.argument("amount", IntegerArgumentType.integer(1, 1_000_000))
						.executes(context -> giveCoins(context, context.getSource().getPlayerOrException(),
								IntegerArgumentType.getInteger(context, "amount")))
						.then(Commands.argument("player", EntityArgument.player())
								.executes(context -> giveCoins(context, EntityArgument.getPlayer(context, "player"),
										IntegerArgumentType.getInteger(context, "amount"))))));
	}

	private static int giveCoins(CommandContext<CommandSourceStack> context, ServerPlayer target, int amount) {
		CommandSourceStack source = context.getSource();
		EconomyState state = EconomyState.get(source.getServer());
		long minted = state.mint(amount);
		if (minted <= 0) {
			source.sendFailure(Component.literal("Лимит эмиссии исчерпан: " + state.minted + " / "
					+ Rl2dopConfig.mintLimit));
			return 0;
		}
		CoinUtil.give(target, (int) minted);
		state.log("эмиссия " + minted + " монет для " + target.getGameProfile().getName()
				+ " (всего " + state.minted + "/" + Rl2dopConfig.mintLimit + ")");
		ModPackets.syncTo(target);
		source.sendSuccess(() -> Component.literal("§aВыдано §f" + minted + "м §aигроку §f"
				+ target.getGameProfile().getName() + "§a. Эмиссия: " + state.minted + "/"
				+ Rl2dopConfig.mintLimit), true);
		if (minted < amount) {
			source.sendSuccess(() -> Component.literal("§eЧасть монет не напечатана: достигнут лимит."), false);
		}
		return (int) minted;
	}

	// ---------- 6: репутация ----------

	private static void registerSetReputation(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("set_reputation")
				.requires(source -> source.hasPermission(2))
				.then(Commands.argument("player", EntityArgument.player())
						.then(Commands.argument("value", IntegerArgumentType.integer(-100, 100))
								.executes(context -> setReputation(context,
										EntityArgument.getPlayer(context, "player"),
										IntegerArgumentType.getInteger(context, "value"))))));
	}

	private static int setReputation(CommandContext<CommandSourceStack> context, ServerPlayer target, int value) {
		CommandSourceStack source = context.getSource();
		ServerLevel level = (ServerLevel) source.getLevel();
		Settlement settlement = SettlementService.nearest(level, target.blockPosition());
		if (settlement == null) {
			settlement = SettlementService.forPlayer(target);
		}
		if (settlement == null) {
			source.sendFailure(Component.literal("Поселений нет."));
			return 0;
		}
		settlement.setReputation(target.getUUID(), value);
		EconomyState.get(source.getServer()).log("репутация " + target.getGameProfile().getName()
				+ " в " + settlement.id + " = " + value);
		EconomyState.get(source.getServer()).setDirty();
		ModPackets.syncTo(target);
		Settlement finalSettlement = settlement;
		source.sendSuccess(() -> Component.literal("§aРепутация §f" + target.getGameProfile().getName()
				+ " §aв §f" + finalSettlement.displayName() + "§a: §f" + value
				+ (value <= Rl2dopConfig.evilReputation ? " §c(стража агрессивна)" : "")), true);
		return 1;
	}

	// ---------- 3/9: поселения ----------

	private static void registerSettleSpawn(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("settle_spawn")
				.requires(source -> source.hasPermission(2))
				.then(Commands.argument("name", StringArgumentType.greedyString())
						.executes(context -> settleSpawn(context, StringArgumentType.getString(context, "name")))));
	}

	private static int settleSpawn(CommandContext<CommandSourceStack> context, String name)
			throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayerOrException();
		ServerLevel level = (ServerLevel) source.getLevel();
		Settlement settlement = EconomyState.get(source.getServer()).createSettlement(name,
				player.blockPosition(), level.dimension().location().toString());
		Displays.ensureBoard(level, settlement, player.blockPosition().above(2));
		source.sendSuccess(() -> Component.literal("§aПоселение «§f" + settlement.displayName()
				+ "§a» создано. Ратуша: §f" + player.blockPosition().toShortString()
				+ "§a. Дальше: §f/settle_bind§a или §f/spawn_npc farmer"), true);
		return 1;
	}

	private static void registerSettleBind(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("settle_bind")
				.requires(source -> source.hasPermission(2))
				.executes(context -> settleBind(context, null))
				.then(Commands.argument("name", StringArgumentType.greedyString())
						.executes(context -> settleBind(context, StringArgumentType.getString(context, "name")))));
	}

	private static int settleBind(CommandContext<CommandSourceStack> context, String name)
			throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayerOrException();
		ServerLevel level = (ServerLevel) source.getLevel();
		String message = SettlementService.bindVillage(level, player.blockPosition(), name);
		source.sendSuccess(() -> Component.literal("§a" + message), true);
		return 1;
	}

	// ---------- 4: рынок ----------

	private static void registerMarketDump(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("market_dump")
				.executes(context -> marketDump(context, null))
				.then(Commands.argument("settlement", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(
								EconomyState.get(context.getSource().getServer()).settlements.keySet(), builder))
						.executes(context -> marketDump(context, StringArgumentType.getString(context, "settlement")))));
	}

	private static int marketDump(CommandContext<CommandSourceStack> context, String settlementId) {
		CommandSourceStack source = context.getSource();
		EconomyState state = EconomyState.get(source.getServer());
		List<String> lines = new ArrayList<>();
		if (settlementId != null) {
			Settlement settlement = state.settlement(settlementId);
			if (settlement == null) {
				source.sendFailure(Component.literal("Поселение не найдено: " + settlementId));
				return 0;
			}
			lines.addAll(dumpSettlement(settlement));
		} else {
			lines.addAll(state.summary());
		}
		for (String line : lines) {
			source.sendSuccess(() -> Component.literal("§7" + line), false);
		}
		return 1;
	}

	private static List<String> dumpSettlement(Settlement settlement) {
		List<String> lines = new ArrayList<>();
		lines.add("Поселение " + settlement.displayName() + " (" + settlement.id + ") dim=" + settlement.dimension);
		lines.add("  казна=" + settlement.treasury + "м  NPC=" + settlement.npcs.size()
				+ "  постройка=" + settlement.buildProgress + "%");
		for (Rl2dopConfig.Good good : Rl2dopConfig.goods.values()) {
			lines.add(String.format("  %s: сток=%d цена=%.2f спрос=%d",
					good.item, settlement.stock(good.item), settlement.price(good.item),
					settlement.demand(good.item)));
		}
		return lines;
	}

	private static void registerResetEconomy(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("reset_economy")
				.requires(source -> source.hasPermission(3))
				.executes(context -> {
					context.getSource().sendFailure(Component.literal(
							"§cЭто удалит все поселения, цены и эмиссию. Повтори: /reset_economy confirm"));
					return 0;
				})
				.then(Commands.literal("confirm").executes(context -> {
					EconomyState state = EconomyState.get(context.getSource().getServer());
					state.reset();
					context.getSource().sendSuccess(() -> Component.literal("§aЭкономика сброшена."), true);
					return 1;
				})));
	}

	// ---------- 8: управление экономикой игроком ----------

	private static void registerSalarySet(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("salary_set")
				.requires(source -> source.hasPermission(2))
				.then(Commands.argument("profession", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(Profession.ids(), builder))
						.then(Commands.argument("amount", IntegerArgumentType.integer(0, 1000))
								.executes(context -> {
									Profession profession = Profession.byId(
											StringArgumentType.getString(context, "profession"));
									int amount = IntegerArgumentType.getInteger(context, "amount");
									Settlement settlement = currentSettlement(context);
									if (settlement == null) {
										context.getSource().sendFailure(Component.literal("Нет поселения."));
										return 0;
									}
									settlement.salaries.put(profession.id(), amount);
									EconomyState.get(context.getSource().getServer()).setDirty();
									context.getSource().sendSuccess(() -> Component.literal("§aЗарплата "
											+ profession.id() + " = " + amount + "м"), true);
									return 1;
								}))));
	}

	private static void registerPriceSet(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("price_set")
				.requires(source -> source.hasPermission(2))
				.then(Commands.argument("item", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(Rl2dopConfig.goodIds(), builder))
						.then(Commands.argument("base", IntegerArgumentType.integer(1, 10000))
								.executes(context -> {
									String item = StringArgumentType.getString(context, "item");
									Rl2dopConfig.Good good = Rl2dopConfig.good(item);
									if (good == null) {
										context.getSource().sendFailure(Component.literal("Нет такого товара в конфиге."));
										return 0;
									}
									good.base = IntegerArgumentType.getInteger(context, "base");
									Settlement settlement = currentSettlement(context);
									if (settlement != null) {
										settlement.prices.put(item, good.base);
									}
									EconomyState.get(context.getSource().getServer()).setDirty();
									context.getSource().sendSuccess(() -> Component.literal(
											"§aБазовая цена " + item + " = " + (int) good.base + "м"), true);
									return 1;
								}))));
	}

	private static void registerShopOpen(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("shop_open")
				.executes(context -> {
					ServerPlayer player = context.getSource().getPlayerOrException();
					ServerLevel level = player.serverLevel();
					CitizenEntity trader = SettlementService.nearestCitizen(level, player, 24.0D);
					Settlement settlement = SettlementService.forPlayer(player);
					if (settlement == null) {
						context.getSource().sendFailure(Component.literal("Нет поселения."));
						return 0;
					}
					if (trader != null) {
						ShopService.open(player, trader);
						return 1;
					}
					ShopService.openHall(player, settlement);
					return 1;
				}));
	}

	private static void registerShelfAdd(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("shelf_add")
				.requires(source -> source.hasPermission(2))
				.then(Commands.argument("item", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(Rl2dopConfig.goodIds(), builder))
						.executes(context -> {
							CommandSourceStack source = context.getSource();
							ServerPlayer player = source.getPlayerOrException();
							ServerLevel level = (ServerLevel) source.getLevel();
							String item = StringArgumentType.getString(context, "item");
							Settlement settlement = SettlementService.forPlayer(player);
							if (settlement == null) {
								source.sendFailure(Component.literal("Нет поселения."));
								return 0;
							}
							BlockPos pos = player.blockPosition().above();
							if (!Displays.addShelf(level, settlement, pos, item)) {
								source.sendFailure(Component.literal("Товар не в конфиге: " + item));
								return 0;
							}
							source.sendSuccess(() -> Component.literal("§aПолка с §f" + item
									+ "§a создана на " + pos.toShortString()), true);
							return 1;
						})));
	}

	private static void registerHallBoard(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("hall_board")
				.requires(source -> source.hasPermission(2))
				.executes(context -> {
					CommandSourceStack source = context.getSource();
					ServerPlayer player = source.getPlayerOrException();
					ServerLevel level = (ServerLevel) source.getLevel();
					Settlement settlement = SettlementService.forPlayer(player);
					if (settlement == null) {
						source.sendFailure(Component.literal("Нет поселения."));
						return 0;
					}
					Displays.ensureBoard(level, settlement, player.blockPosition().above(2));
					source.sendSuccess(() -> Component.literal("§aТабло ратуши обновлено."), true);
					return 1;
				}));
	}

	private static void registerNpcList(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("npc_list")
				.requires(source -> source.hasPermission(2))
				.executes(context -> {
					CommandSourceStack source = context.getSource();
					ServerPlayer player = source.getPlayerOrException();
					ServerLevel level = player.serverLevel();
					List<CitizenEntity> citizens = level.getEntitiesOfClass(CitizenEntity.class,
							player.getBoundingBox().inflate(96.0D));
					if (citizens.isEmpty()) {
						source.sendSuccess(() -> Component.literal("§7NPC рядом нет."), false);
						return 0;
					}
					for (CitizenEntity citizen : citizens) {
						String line = "§e" + citizen.getCitizenName() + "§7 " + citizen.getProfession().id()
								+ " · " + citizen.getCitizenState().id()
								+ " · кошелёк " + citizen.getWallet() + "м"
								+ " · голод " + citizen.getHunger() + "%"
								+ " · поселение " + citizen.getSettlementId();
						source.sendSuccess(() -> Component.literal(line), false);
					}
					return citizens.size();
				}));
	}

	private static void registerEconLog(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("econ_log")
				.requires(source -> source.hasPermission(2))
				.executes(context -> econLog(context, 10))
				.then(Commands.argument("lines", IntegerArgumentType.integer(1, 100))
						.executes(context -> econLog(context,
								IntegerArgumentType.getInteger(context, "lines")))));
	}

	private static int econLog(CommandContext<CommandSourceStack> context, int lines) {
		EconomyState state = EconomyState.get(context.getSource().getServer());
		int from = Math.max(0, state.log.size() - lines);
		for (int i = from; i < state.log.size(); i++) {
			String line = state.log.get(i);
			context.getSource().sendSuccess(() -> Component.literal("§8" + line), false);
		}
		return 1;
	}

	private static void registerRoot(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("rl2dop")
				.executes(context -> {
					CommandSourceStack source = context.getSource();
					String[] help = {
							"§6Rl2dop §7— экономика поселений",
							"§f/spawn_npc <profession> [name]§7 — заспавнить жителя",
							"§f/give_coins <amount> [player]§7 — напечатать монеты (лимит эмиссии)",
							"§f/set_reputation <player> <value>§7 — репутация (-100..100)",
							"§f/settle_spawn <name>§7 — ратуша/поселение в текущей точке",
							"§f/settle_bind [name]§7 — привязать к ванильной деревне",
							"§f/market_dump [settlement]§7 — цены, сток, спрос",
							"§f/shop_open§7 — открыть торговый экран",
							"§f/shelf_add <item>§7 — полка с живым стоком",
							"§f/salary_set <profession> <amount>§7 — зарплата",
							"§f/price_set <item> <base>§7 — базовая цена",
							"§f/npc_list§7, §f/econ_log [n]§7, §f/reset_economy confirm"
					};
					for (String line : help) {
						source.sendSuccess(() -> Component.literal(line), false);
					}
					return 1;
				}));
	}

	private static Settlement currentSettlement(CommandContext<CommandSourceStack> context)
			throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		return SettlementService.forPlayer(player);
	}

	@SuppressWarnings("unused")
	private static Component plain(String text) {
		return Component.literal(text).withStyle(ChatFormatting.WHITE);
	}
}
