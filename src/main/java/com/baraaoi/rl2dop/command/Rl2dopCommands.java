package com.baraaoi.rl2dop.command;

import com.baraaoi.rl2dop.Rl2dopConfig;
import com.baraaoi.rl2dop.economy.CoinType;
import com.baraaoi.rl2dop.economy.Money;
import com.baraaoi.rl2dop.economy.Displays;
import com.baraaoi.rl2dop.economy.EconomyState;
import com.baraaoi.rl2dop.economy.EconomyTicker;
import com.baraaoi.rl2dop.economy.Settlement;
import com.baraaoi.rl2dop.economy.SettlementBuilds;
import com.baraaoi.rl2dop.economy.SettlementService;
import com.baraaoi.rl2dop.economy.ShopService;
import com.baraaoi.rl2dop.economy.SignBoards;
import com.baraaoi.rl2dop.economy.WalletService;
import com.baraaoi.rl2dop.registry.ModItems;
import com.baraaoi.rl2dop.entity.CitizenEntity;
import com.baraaoi.rl2dop.entity.Profession;
import com.baraaoi.rl2dop.net.ModPackets;
import com.baraaoi.rl2dop.economy.VillageLife;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
			registerSettleBuild(dispatcher);
			registerHouseAdd(dispatcher);
			registerSettleMayor(dispatcher);
			registerWallet(dispatcher);
			registerMarketDump(dispatcher);
			registerResetEconomy(dispatcher);
			registerSalarySet(dispatcher);
			registerPriceSet(dispatcher);
			registerShopOpen(dispatcher);
			registerShelfAdd(dispatcher);
			registerHallBoard(dispatcher);
			registerShopMarker(dispatcher);
			registerNpcProfession(dispatcher);
			registerNpcList(dispatcher);
			registerSettleTavern(dispatcher);
			registerSettleSettlers(dispatcher);
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
		ServerLevel level = source.getLevel();
		BlockPos pos = targetPos(source);
		Settlement settlement = settlementAt(source, pos);
		if (settlement == null) {
			source.sendFailure(Component.literal("Нет поселения рядом. Создай: /settle_spawn <имя>"));
			return 0;
		}
		Profession profession = Profession.byId(professionId);
		CitizenEntity citizen = SettlementService.spawnCitizen(level, settlement, profession, pos, name);
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
						.executes(context -> giveCoins(context, context.getSource().getPlayer(),
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
		String who;
		if (target != null) {
			if (Rl2dopConfig.giveWalletOnMint) {
				Money.ensureWallet(target);
			}
			Money.give(target, (int) minted);
			ModPackets.syncTo(target);
			who = target.getGameProfile().getName();
			state.log("эмиссия " + minted + " монет для " + who + " (всего " + state.minted + "/"
					+ Rl2dopConfig.mintLimit + ")");
		} else {
			// из консоли печатаем прямо в казну ближайшего поселения
			Settlement settlement = settlementAt(source, targetPos(source));
			if (settlement != null) {
				settlement.treasury += minted;
				who = "казна " + settlement.displayName();
			} else {
				who = "никуда (нет поселений)";
			}
			state.log("эмиссия " + minted + " монет в " + who + " (всего " + state.minted + "/"
					+ Rl2dopConfig.mintLimit + ")");
		}
		String targetName = who;
		source.sendSuccess(() -> Component.literal("§aНапечатано §f" + minted + "м §a→ §f" + targetName
				+ "§a. Эмиссия: " + state.minted + "/" + Rl2dopConfig.mintLimit), true);
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
		ServerLevel level = source.getLevel();
		BlockPos raw = targetPos(source);
		BlockPos pos = SettlementService.surface(level, raw.getX(), raw.getZ(), raw.getY());
		Settlement settlement = EconomyState.get(source.getServer()).createSettlement(name, pos,
				level.dimension().location().toString());
		Displays.ensureBoard(level, settlement, pos.above(2));
		// таблички нужд вывешиваем сразу: потребности порождает стройка, а не «пустое место»
		SignBoards.install(level, settlement);
		int progress = Rl2dopConfig.autoBuild ? SettlementBuilds.buildInstant(level, settlement)
				: settlement.buildProgress;
		EconomyTicker.settlementStep(source.getServer(), EconomyState.get(source.getServer()));
		int houses = settlement.houses.size();
		CitizenEntity mayor = SettlementService.findMayor(level, settlement);
		source.sendSuccess(() -> Component.literal("§aПоселение «§f" + settlement.displayName()
				+ "§a» создано. Ратуша: §f" + pos.toShortString()
				+ "§a, постройка §f" + progress + "%§a, домов §f" + houses
				+ "§a, глава: §f" + (mayor == null ? "не выбран" : mayor.getCitizenName())
				+ "§a. Таблички нужд уже висят. Дальше: §f/settle_bind§a, §f/house_add§a или §f/spawn_npc farmer"), true);
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
		ServerLevel level = source.getLevel();
		String message = SettlementService.bindVillage(level, targetPos(source), name);
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
		List<CitizenEntity> loaded = source.getLevel().getEntitiesOfClass(CitizenEntity.class,
				new net.minecraft.world.phys.AABB(targetPos(source)).inflate(256.0D));
		List<String> registered = new ArrayList<>();
		for (CitizenEntity citizen : loaded) {
			registered.add(citizen.getCitizenName() + "/" + citizen.getProfession().id() + "/"
					+ citizen.getSettlementId());
		}
		lines.add("загружено NPC рядом: " + loaded.size() + " " + registered);
		for (String line : lines) {
			source.sendSuccess(() -> Component.literal("§7" + line), false);
		}
		return 1;
	}

	private static List<String> dumpSettlement(Settlement settlement) {
		List<String> lines = new ArrayList<>();
		lines.add("Поселение " + settlement.displayName() + " (" + settlement.id + ") dim=" + settlement.dimension);
		lines.add("  казна=" + settlement.treasury + "м"
				+ (Rl2dopConfig.infiniteNpcMoney ? " §a(бесконечная)§7" : "")
				+ "  NPC=" + settlement.npcs.size()
				+ "  постройка=" + settlement.buildProgress + "%"
				+ "  лавка=" + settlement.shop().toShortString()
				+ "  домов=" + settlement.houses.size()
				+ "  мастерских=" + settlement.workSites.size()
				+ "  табличек=" + settlement.boards.size()
				+ "  таверна=" + (settlement.hasTavern() ? settlement.tavern().toShortString() : "нет")
				+ "  без работы=" + settlement.joblessCount
				+ "  вакансий=" + VillageLife.vacancies(settlement).size()
				+ "  мест под дома=" + (SettlementBuilds.plotCount() - settlement.houses.size()));
		if (settlement.hasHouseGoal() && settlement.houseGoalPos() != null) {
			lines.add("  §aстроят дом: §f" + settlement.houseGoalPos().toShortString()
					+ "§7 (без жилья сейчас: " + settlement.buildDesire + ")");
		}
		for (Map.Entry<String, String> site : settlement.workSites.entrySet()) {
			lines.add("  работа " + site.getKey() + ": " + site.getValue().replace(';', ','));
		}
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
				.then(Commands.argument("item", ResourceLocationArgument.id())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(Rl2dopConfig.goodIds(), builder))
						.then(Commands.argument("base", IntegerArgumentType.integer(1, 10000))
								.executes(context -> {
									String item = ResourceLocationArgument.getId(context, "item").toString();
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
									Rl2dopConfig.save();
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
				.then(Commands.argument("item", ResourceLocationArgument.id())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(Rl2dopConfig.goodIds(), builder))
						.executes(context -> {
							CommandSourceStack source = context.getSource();
							ServerLevel level = source.getLevel();
							String item = ResourceLocationArgument.getId(context, "item").toString();
							BlockPos pos = targetPos(source).above();
							Settlement settlement = settlementAt(source, pos);
							if (settlement == null) {
								source.sendFailure(Component.literal("Нет поселения."));
								return 0;
							}
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
					ServerLevel level = source.getLevel();
					BlockPos pos = targetPos(source).above(2);
					Settlement settlement = settlementAt(source, pos);
					if (settlement == null) {
						source.sendFailure(Component.literal("Нет поселения."));
						return 0;
					}
					Displays.ensureBoard(level, settlement, pos);
					source.sendSuccess(() -> Component.literal("§aТабло ратуши обновлено."), true);
					return 1;
				}));
	}

	private static void registerShopMarker(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("shop_marker")
				.requires(source -> source.hasPermission(2))
				.executes(context -> {
					CommandSourceStack source = context.getSource();
					BlockPos pos = targetPos(source);
					Settlement settlement = settlementAt(source, pos);
					if (settlement == null) {
						source.sendFailure(Component.literal("Нет поселения."));
						return 0;
					}
					settlement.setShop(pos);
					EconomyState.get(source.getServer()).setDirty();
					source.sendSuccess(() -> Component.literal("§aМагазин поселения «" + settlement.displayName()
							+ "» привязан к " + pos.toShortString()), true);
					return 1;
				}));
	}

	private static void registerNpcProfession(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("npc_profession")
				.requires(source -> source.hasPermission(2))
				.then(Commands.argument("profession", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(Profession.ids(), builder))
						.executes(context -> {
							CommandSourceStack source = context.getSource();
							ServerLevel level = source.getLevel();
							BlockPos pos = targetPos(source);
							CitizenEntity citizen = nearestCitizen(level, pos);
							if (citizen == null) {
								source.sendFailure(Component.literal("Рядом нет NPC."));
								return 0;
							}
							Profession profession = Profession.byId(StringArgumentType.getString(context, "profession"));
							citizen.setProfession(profession);
							Settlement settlement = settlementAt(source, pos);
							if (settlement != null) {
								citizen.setWorkPos(SettlementService.workSpot(level, settlement, profession));
							}
							source.sendSuccess(() -> Component.literal("§a" + citizen.getCitizenName()
									+ " теперь " + profession.id()), true);
							return 1;
						})));
	}

	private static CitizenEntity nearestCitizen(ServerLevel level, BlockPos pos) {
		List<CitizenEntity> citizens = level.getEntitiesOfClass(CitizenEntity.class,
				new net.minecraft.world.phys.AABB(pos).inflate(24.0D));
		CitizenEntity best = null;
		double bestDistance = Double.MAX_VALUE;
		for (CitizenEntity citizen : citizens) {
			double distance = citizen.blockPosition().distSqr(pos);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = citizen;
			}
		}
		return best;
	}

	private static void registerNpcList(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("npc_list")
				.requires(source -> source.hasPermission(2))
				.executes(context -> {
					CommandSourceStack source = context.getSource();
					ServerLevel level = source.getLevel();
					BlockPos center = targetPos(source);
					List<CitizenEntity> citizens = level.getEntitiesOfClass(CitizenEntity.class,
							new net.minecraft.world.phys.AABB(center).inflate(96.0D));
					if (citizens.isEmpty()) {
						source.sendSuccess(() -> Component.literal("§7NPC рядом нет."), false);
						return 0;
					}
					for (CitizenEntity citizen : citizens) {
						String line = "§e" + citizen.getCitizenName() + "§7 " + citizen.getProfession().id()
								+ " · §b" + citizen.getActivity().id() + "§7"
								+ " · " + citizen.getCitizenState().id()
								+ " · кошелёк " + citizen.getWallet() + "м"
								+ " · голод " + citizen.getHunger() + "%"
								+ " · дом " + (citizen.getHomePos() == null ? "нет" : "есть")
								+ " · до работы " + workDistance(citizen)
								+ " · поселение " + citizen.getSettlementId();
						source.sendSuccess(() -> Component.literal(line), false);
					}
					return citizens.size();
				}));
	}

	/** Сколько блоков до рабочего места (проверка, что житель держится у своей постройки). */
	private static String workDistance(CitizenEntity citizen) {
		BlockPos work = citizen.getWorkPos();
		if (work == null) {
			return "—";
		}
		return Math.round(Math.sqrt(citizen.blockPosition().distSqr(work))) + "м";
	}

	private static void registerSettleTavern(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("settle_tavern")
				.requires(source -> source.hasPermission(2))
				.executes(context -> settleTavern(context, null))
				.then(Commands.argument("settlement", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(
								EconomyState.get(context.getSource().getServer()).settlements.keySet(), builder))
						.executes(context -> settleTavern(context,
								StringArgumentType.getString(context, "settlement")))));
	}

	private static int settleTavern(CommandContext<CommandSourceStack> context, String settlementId)
			throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		Settlement settlement = resolveSettlement(source, settlementId, targetPos(source));
		if (settlement == null) {
			source.sendFailure(Component.literal("§cПоселение не найдено — встань рядом с ратушей "
					+ "или укажи id: /settle_tavern <settlement>"));
			return 0;
		}
		BlockPos at = SettlementBuilds.buildTavern(source.getLevel(), settlement);
		if (at == null) {
			source.sendFailure(Component.literal("§cТаверна не встала: чанки вокруг ратуши не выгружены."));
			return 0;
		}
		SignBoards.install(source.getLevel(), settlement);
		source.sendSuccess(() -> Component.literal("§aТаверна у §f" + at.toShortString()
				+ "§a. Тут ждут работу приезжие и проходят перерывы; доска вакансий — рядом с дверью."), true);
		return 1;
	}

	private static void registerSettleSettlers(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("settle_settlers")
				.requires(source -> source.hasPermission(2))
				.executes(context -> settleSettlers(context, null, 1))
				.then(Commands.argument("settlement", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(
								EconomyState.get(context.getSource().getServer()).settlements.keySet(), builder))
						.executes(context -> settleSettlers(context,
								StringArgumentType.getString(context, "settlement"), 1))
						.then(Commands.argument("count", IntegerArgumentType.integer(1, 8))
								.executes(context -> settleSettlers(context,
										StringArgumentType.getString(context, "settlement"),
										IntegerArgumentType.getInteger(context, "count"))))));
	}

	private static int settleSettlers(CommandContext<CommandSourceStack> context, String settlementId, int count)
			throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		Settlement settlement = resolveSettlement(source, settlementId, targetPos(source));
		if (settlement == null) {
			source.sendFailure(Component.literal("§cПоселение не найдено — укажи id: /settle_settlers <settlement> [n]"));
			return 0;
		}
		int spawned = VillageLife.invite(source.getLevel(), settlement, EconomyState.get(source.getServer()), count);
		source.sendSuccess(() -> Component.literal("§aПринято жителей: §f" + spawned
				+ "§a. " + (settlement.joblessCount > 0 ? "Часть без работы — ждут вакансию у таверны."
						: "Всех расписали по вакансиям.")), true);
		return spawned;
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
						"§f/settle_build [settlement] [instant|grow|reset]§7 — постройки поселения",
						"§f/house_add [settlement]§7 — поставить жилой дом и поселить в него NPC",
						"§f/settle_mayor [settlement] [remove]§7 — глава: стоит у табличек, скупает всё без торга",
						"§f/wallet [give|deposit|withdraw|change]§7 — кошелёк и монеты",
							"§f/market_dump [settlement]§7 — цены, сток, спрос",
							"§f/shop_open§7 — открыть торговый экран",
							"§f/shelf_add <item>§7 — полка с живым стоком",
							"§f/shop_marker§7 — точка входа магазина поселения",
							"§f/salary_set <profession> <amount>§7 — зарплата",
							"§f/price_set <item> <base>§7 — базовая цена",
							"§f/npc_profession <profession>§7 — сменить профессию ближайшему NPC",
							"§f/npc_list§7, §f/econ_log [n]§7, §f/reset_economy confirm"
					};
					for (String line : help) {
						source.sendSuccess(() -> Component.literal(line), false);
					}
					return 1;
				}));
	}

	// ---------- 9: постройки поселения ----------

	private static void registerSettleBuild(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("settle_build")
				.requires(source -> source.hasPermission(2))
				.executes(context -> settleBuild(context, null, "instant"))
				.then(Commands.argument("settlement", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(
								EconomyState.get(context.getSource().getServer()).settlements.keySet(), builder))
						.executes(context -> settleBuild(context,
								StringArgumentType.getString(context, "settlement"), "instant"))
						.then(Commands.argument("mode", StringArgumentType.word())
								.suggests((context, builder) -> SharedSuggestionProvider.suggest(
										List.of("instant", "grow", "reset"), builder))
								.executes(context -> settleBuild(context,
										StringArgumentType.getString(context, "settlement"),
										StringArgumentType.getString(context, "mode"))))));
	}

	private static int settleBuild(CommandContext<CommandSourceStack> context, String settlementId, String mode) {
		CommandSourceStack source = context.getSource();
		ServerLevel level = source.getLevel();
		Settlement settlement = settlementId == null
				? settlementAt(source, targetPos(source))
				: EconomyState.get(source.getServer()).settlement(settlementId);
		if (settlement == null) {
			source.sendFailure(Component.literal("Поселение не найдено."));
			return 0;
		}
		switch (mode) {
			case "reset" -> {
				settlement.buildProgress = 0;
				EconomyState.get(source.getServer()).setDirty();
				source.sendSuccess(() -> Component.literal("§eПостройка §f" + settlement.displayName()
						+ "§e сброшена: дальше растёт автоматически (autoBuild)."), true);
				return 1;
			}
			case "grow" -> {
				int progress = SettlementBuilds.build(level, settlement, Math.max(1, Rl2dopConfig.buildBlocksPerTick));
				EconomyTicker.settlementStep(source.getServer(), EconomyState.get(source.getServer()));
				source.sendSuccess(() -> Component.literal("§aПостроено до §f" + progress + "%§a (шаг §f"
						+ Rl2dopConfig.buildBlocksPerTick + "§a блоков), материалов съедено на этом шаге: §f"
						+ settlement.stock("minecraft:cobblestone") + "§a камня на складе."), true);
				return 1;
			}
			default -> {
				int progress = SettlementBuilds.buildInstant(level, settlement);
				EconomyTicker.settlementStep(source.getServer(), EconomyState.get(source.getServer()));
				SignBoards.refresh(level, settlement);
				EconomyState.get(source.getServer()).setDirty();
				int houses = settlement.houses.size();
				source.sendSuccess(() -> Component.literal("§aРатуша, площадь, лавка, склад и §f" + houses
						+ "§a домов поселения §f" + settlement.displayName() + "§a: постройка §f" + progress
						+ "%§a, точка магазина §f" + settlement.shop().toShortString()
						+ "§a, таблички нужд внутри зала."), true);
				return 1;
			}
		}
	}

	// ---------- 1: кошелёк и монеты ----------

	// ---------- 9: свои дома (/house_add) и глава поселения (/settle_mayor) ----------

	private static void registerHouseAdd(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("house_add")
				.requires(source -> source.hasPermission(2))
				.executes(context -> houseAdd(context, null))
				.then(Commands.argument("settlement", StringArgumentType.word())
						.executes(context -> houseAdd(context, StringArgumentType.getString(context, "settlement")))));
	}

	/**
	 * Поставить жилой дом: в точке прицела (или под ногами), привязать к поселению. Дом встаёт на
	 * грунт (фундамент достраивается вниз), записывается в {@code Settlement.houses}, и в него
	 * сразу расселяются жители без жилья.
	 */
	private static int houseAdd(CommandContext<CommandSourceStack> context, String settlementId)
			throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerLevel level = source.getLevel();
		Settlement settlement = resolveSettlement(source, settlementId, targetPos(source));
		if (settlement == null) {
			source.sendFailure(Component.literal("§cПоселение не найдено — встань рядом с ратушей "
					+ "или укажи id: /house_add <settlement>"));
			return 0;
		}
		BlockPos raw = targetPos(source);
		// с-console ставим дом восточнее стартовых застроек, чтобы не залезать на них
			BlockPos free = SettlementBuilds.freePlot(source.getLevel(), settlement);
			BlockPos pos = source.getEntity() == null
					? (free != null ? free : settlement.hall().offset(SettlementBuilds.HOUSE_X + 8
							+ settlement.houses.size() * 8, 0, SettlementBuilds.HOUSE_Z))
					: BlockPos.containing(source.getEntity().getX(), raw.getY(), source.getEntity().getZ());
		BlockPos built = SettlementBuilds.buildHouse(level, settlement, pos);
		if (built == null) {
			source.sendFailure(Component.literal("§cДом не встал: место занято игроком или чанк выгружен."));
			return 0;
		}
		int houses = settlement.houses.size();
		int settled = SettlementService.assignHomes(level, settlement);
		source.sendSuccess(() -> Component.literal("§aДом поставлен в §f" + built.toShortString()
				+ "§a. Домов в поселении: §f" + houses + "§a, поселено жителей: §f" + settled
				+ "§a. Вход с южной стороны, внутри сундук и верстак. Откат: §f/settle_build <id> reset"), true);
		return 1;
	}

	private static void registerSettleMayor(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("settle_mayor")
				.requires(source -> source.hasPermission(2))
				.executes(context -> settleMayor(context, null))
				.then(Commands.argument("settlement", StringArgumentType.word())
						.executes(context -> settleMayor(context, StringArgumentType.getString(context, "settlement")))
						.then(Commands.literal("remove")
								.executes(context -> removeMayor(context,
										StringArgumentType.getString(context, "settlement"))))));
	}

	/** Выбрать/переизбрать главу: стоит у табличек, скупает всё без торга. */
	private static int settleMayor(CommandContext<CommandSourceStack> context, String settlementId)
			throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerLevel level = source.getLevel();
		Settlement settlement = resolveSettlement(source, settlementId, targetPos(source));
		if (settlement == null) {
			source.sendFailure(Component.literal("§cПоселение не найдено."));
			return 0;
		}
		if (!SettlementBuilds.hallStanding(settlement)) {
			SignBoards.install(level, settlement);
		}
		CitizenEntity old = SettlementService.findMayor(level, settlement);
		if (old != null) {
			old.discard();
		}
		CitizenEntity mayor = SettlementService.spawnMayor(level, settlement);
		if (mayor == null) {
			source.sendFailure(Component.literal("§cНе удалось поставить главу (нет ратуши?)."));
			return 0;
		}
		mayor.refreshNameTag();
		int bonus = (int) Math.round((Math.max(1.0D, Rl2dopConfig.mayorBuyBonus) - 1.0D) * 100.0D);
		source.sendSuccess(() -> Component.literal("§aГлавой поселения «§f" + settlement.displayName()
				+ "§a» назначен §f" + mayor.getCitizenName() + "§a. Он стоит у табличек и скупает всё без торга "
				+ "(скупка +§f" + bonus + "%§a). Снять: §f/settle_mayor " + settlement.id + " remove"), true);
		return 1;
	}

	private static int removeMayor(CommandContext<CommandSourceStack> context, String settlementId)
			throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		Settlement settlement = resolveSettlement(source, settlementId, targetPos(source));
		if (settlement == null) {
			source.sendFailure(Component.literal("§cПоселение не найдено."));
			return 0;
		}
		CitizenEntity mayor = SettlementService.findMayor(source.getLevel(), settlement);
		if (mayor == null) {
			source.sendFailure(Component.literal("§cГлавы нет."));
			return 0;
		}
		mayor.discard();
		settlement.mayor = "";
		EconomyState.get(source.getServer()).setDirty();
		source.sendSuccess(() -> Component.literal("§aГлава снят, его запись удалена."), true);
		return 1;
	}

	/** Поселение по id, иначе ближайшее к точке, иначе «по умолчанию». */
	private static Settlement resolveSettlement(CommandSourceStack source, String settlementId, BlockPos at) {
		if (settlementId != null && !settlementId.isBlank()) {
			Settlement settlement = EconomyState.get(source.getServer()).settlement(settlementId);
			if (settlement != null) {
				return settlement;
			}
		}
		Settlement nearest = SettlementService.nearest(source.getLevel(), at);
		return nearest != null ? nearest : EconomyState.get(source.getServer()).defaultSettlement();
	}

	private static void registerWallet(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("wallet")
				.executes(context -> wallet(context.getSource(), "open"))
				.then(Commands.literal("give")
						.executes(context -> wallet(context.getSource(), "give")))
				.then(Commands.literal("deposit")
						.executes(context -> wallet(context.getSource(), "deposit")))
				.then(Commands.literal("withdraw")
						.executes(context -> wallet(context.getSource(), "withdraw")))
				.then(Commands.literal("change")
						.executes(context -> wallet(context.getSource(), "change")))
				.then(Commands.literal("coins")
						.requires(source -> source.hasPermission(2))
						.then(Commands.argument("type", StringArgumentType.word())
								.suggests((context, builder) -> SharedSuggestionProvider.suggest(COIN_IDS, builder))
								.then(Commands.argument("count", IntegerArgumentType.integer(1, 10000))
										.executes(context -> giveCoinStacks(context,
												StringArgumentType.getString(context, "type"),
												IntegerArgumentType.getInteger(context, "count")))))));
	}

	private static int wallet(CommandSourceStack source, String mode) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("§cКошелёк открывается только из игры (нужен игрок)."));
			return 0;
		}
		switch (mode) {
			case "give" -> {
				if (Money.ensureWallet(player)) {
					player.displayClientMessage(Component.literal("§aКошелёк выдан. ПКМ — открыть."), false);
				} else {
					player.displayClientMessage(Component.literal("§7Кошелёк уже есть в инвентаре."), false);
				}
			}
			case "deposit" -> WalletService.handle(player, WalletService.ACTION_DEPOSIT);
			case "withdraw" -> WalletService.handle(player, WalletService.ACTION_WITHDRAW);
			case "change" -> {
				int total = Money.total(player);
				Money.setTotal(player, total);
				player.displayClientMessage(Component.literal("§aРазменяно: §f"
						+ Money.describe(Money.purseCounts(player)) + " §a(= " + total + "м)"), false);
			}
			default -> {
				WalletService.open(player);
				player.displayClientMessage(Component.literal("§6В карманах §f"
						+ Money.describe(Money.purseCounts(player)) + " §6= §f" + Money.total(player) + "м"), false);
			}
		}
		return 1;
	}

	private static int giveCoinStacks(CommandContext<CommandSourceStack> context, String typeId, int count)
			throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayerOrException();
		CoinType type = coinType(typeId);
		if (type == null) {
			source.sendFailure(Component.literal("§cМонеты: " + String.join("/", COIN_IDS)));
			return 0;
		}
		int left = count;
		while (left > 0) {
			int chunk = Math.min(64, left);
			ItemStack stack = new ItemStack(type.item(), chunk);
			if (!player.getInventory().add(stack)) {
				player.drop(stack, false);
			}
			left -= chunk;
		}
		player.getInventory().setChanged();
		ModPackets.syncTo(player);
		source.sendSuccess(() -> Component.literal("§aВыдано §f" + count + " " + type.itemId()
				+ "§a (=" + (count * type.value()) + "м)"), true);
		return count;
	}

	private static final List<String> COIN_IDS = List.of("bronze", "iron", "gold");

	private static CoinType coinType(String id) {
		String key = id.toLowerCase(Locale.ROOT);
		for (CoinType type : CoinType.values()) {
			if (type.itemId().equals(key) || type.name().toLowerCase(Locale.ROOT).equals(key)) {
				return type;
			}
		}
		return null;
	}

	private static Settlement currentSettlement(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		return settlementAt(source, targetPos(source));
	}

	private static Settlement settlementAt(CommandSourceStack source, BlockPos pos) {
		Settlement settlement = SettlementService.nearest(source.getLevel(), pos);
		if (settlement != null) {
			return settlement;
		}
		return EconomyState.get(source.getServer()).defaultSettlement();
	}

	/** Позиция источника: игрок или блок консоли/командного блока. */
	private static BlockPos targetPos(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		return player != null ? player.blockPosition() : BlockPos.containing(source.getPosition());
	}
}
