package com.baraaoi.rl2dop.economy;

import com.baraaoi.rl2dop.Rl2dopConfig;
import com.baraaoi.rl2dop.entity.Profession;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Таблички поселения: имя и казна, нужды, скупка. Как в Millénaire — список висит на знаках,
 * а не прячется за командами.
 *
 * <p>Когда ратуша построена, знаки стоят **внутри зала** у дальней стены (их не видно с улицы,
 * зато не сносит дождём и не разбирает). До постройки и при выключенном автостроении — на площади.
 * Тексты товаров идут через {@code rl2dop.short.<item>}, то есть на языке игрока (русский по умолчанию).
 */
public final class SignBoards {
	private SignBoards() {
	}

	public static final String KIND_NAME = "name";
	public static final String KIND_NEEDS = "needs";
	public static final String KIND_MARKET = "market";
	/** Доска вакансий у таверны: кого поселение готово принять. */
	public static final String KIND_JOBS = "jobs";

	private static final int[] SPOTS_X = {-2, 0, 2};
	private static final String[] SPOTS_KIND = {KIND_NEEDS, KIND_NAME, KIND_MARKET, KIND_JOBS};
	/** Глубина расстановки: внутри зала у дальней стены или на площади перед ратушей. */
	private static final int INSIDE_Z_OFFSET = -SettlementBuilds.HALL_HALF_Z + 2;
	private static final int OUTSIDE_Z = 7;

	/** Позиция знака выбранного типа для поселения. */
	public static BlockPos pos(Settlement settlement, String kind) {
		BlockPos hall = settlement.hall();
		if (KIND_JOBS.equals(kind)) {
			// у двери таверны, а пока её нет — на площади напротив входа в ратушу
			return settlement.hasTavern()
					? settlement.tavern().offset(3, 1, 0)
					: hall.offset(-SPOTS_X[0] - 4, 1, SettlementBuilds.HALL_HALF_Z + 3);
		}
		int index = KIND_NEEDS.equals(kind) ? 0 : (KIND_NAME.equals(kind) ? 1 : 2);
		int x = SPOTS_X[index];
		int z = SettlementBuilds.hallStanding(settlement) ? INSIDE_Z_OFFSET : OUTSIDE_Z;
		return hall.offset(x, z == OUTSIDE_Z ? 1 : 2, z);
	}

	/** Точка, где стоит глава поселения: лицом к табличкам, в проходе зала. */
	public static BlockPos mayorPost(ServerLevel level, Settlement settlement) {
		BlockPos hall = settlement.hall();
		BlockPos post = SettlementBuilds.hallStanding(settlement)
				? hall.offset(0, 1, INSIDE_Z_OFFSET + 2)
				: hall.offset(0, 1, OUTSIDE_Z - 2);
		return level == null ? post : SettlementBuilds.safeStand(level, post);
	}

	/**
	 * Поставить стойки со знаками и запомнить координаты. Идемпотентно, а если ратуша построилась
	 * и знакам пора внутрь — старые уличные стойки убираются (мы их поставили, значит наши).
	 */
	public static void install(ServerLevel level, Settlement settlement) {
		for (String kind : SPOTS_KIND) {
			BlockPos signPos = pos(settlement, kind);
			detach(level, settlement, kind, signPos);
			BlockPos post = signPos.below();
			if (level.hasChunkAt(post) && (level.getBlockState(post).isAir() || level.getBlockState(post).canBeReplaced())) {
				level.setBlockAndUpdate(post, Blocks.OAK_FENCE.defaultBlockState());
			}
			if (level.hasChunkAt(signPos) && !level.getBlockState(signPos).is(Blocks.OAK_SIGN)) {
				BlockState sign = Blocks.OAK_SIGN.defaultBlockState()
						.setValue(BlockStateProperties.ROTATION_16, SettlementBuilds.hallStanding(settlement) ? 0 : 8);
				level.setBlockAndUpdate(signPos, sign);
			}
			String entry = signPos.getX() + ";" + signPos.getY() + ";" + signPos.getZ() + ";" + kind;
			if (!settlement.boards.contains(entry)) {
				settlement.boards.add(entry);
			}
		}
		EconomyState.get(level.getServer()).setDirty();
		refresh(level, settlement);
	}

	/** Убрать запись (и блок знака), если тот же тип таблички стоит в другой точке. */
	private static void detach(ServerLevel level, Settlement settlement, String kind, BlockPos keep) {
		for (String entry : new ArrayList<>(settlement.boards)) {
			String[] parts = entry.split(";");
			if (parts.length != 4 || !kind.equals(parts[3])) {
				continue;
			}
			BlockPos old;
			try {
				old = new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
			} catch (NumberFormatException e) {
				settlement.boards.remove(entry);
				continue;
			}
			if (old.equals(keep)) {
				continue;
			}
			settlement.boards.remove(entry);
			if (level.hasChunkAt(old)) {
				if (level.getBlockState(old).is(Blocks.OAK_SIGN)) {
					level.setBlockAndUpdate(old, Blocks.AIR.defaultBlockState());
				}
				BlockPos post = old.below();
				if (level.getBlockState(post).is(Blocks.OAK_FENCE)) {
					level.setBlockAndUpdate(post, Blocks.AIR.defaultBlockState());
				}
			}
		}
	}

	/** Обновить текст всех табличек этого поселения. */
	public static void refresh(ServerLevel level, Settlement settlement) {
		if (!Rl2dopConfig.needsOnSigns || settlement.boards.isEmpty()) {
			return;
		}
		for (String entry : new ArrayList<>(settlement.boards)) {
			String[] parts = entry.split(";");
			if (parts.length != 4) {
				continue;
			}
			BlockPos pos;
			try {
				pos = new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
			} catch (NumberFormatException e) {
				continue;
			}
			if (!level.hasChunkAt(pos) || !(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) {
				continue;
			}
			write(sign, lines(settlement, parts[3]));
		}
	}

	/** Обновить таблички всех поселений (раз в displayRefreshTicks). */
	public static void refreshAll(MinecraftServer server) {
		EconomyState state = EconomyState.get(server);
		for (Settlement settlement : state.all()) {
			ServerLevel level = Displays.levelOf(server, settlement.dimension);
			if (level != null) {
				refresh(level, settlement);
			}
		}
	}

	/** Кого поселение готово принять прямо сейчас и что оно само задумало построить. */
	private static List<Line> jobLines(Settlement settlement) {
		List<Line> lines = new ArrayList<>();
		lines.add(new Line(Component.literal("вакансии").withStyle(ChatFormatting.GOLD), null));
		List<Profession> free = VillageLife.vacancies(settlement);
		int shown = 0;
		for (Profession profession : free) {
			if (shown >= 2) {
				break;
			}
			MutableComponent text = Component.translatable(profession.translationKey())
					.append(Component.literal(" " + settlement.salaryFor(profession) + "м"));
			lines.add(new Line(text.withStyle(ChatFormatting.WHITE), null));
			shown++;
		}
		if (shown == 0) {
			lines.add(new Line(Component.literal("мест занято всё").withStyle(ChatFormatting.GREEN), null));
		}
		String plan = settlement.hasHouseGoal() ? "строим дом"
				: (settlement.buildDesire > 0 ? "нужны дома: " + settlement.buildDesire : "домов хватает");
		lines.add(new Line(Component.literal(plan + " · без работы " + settlement.joblessCount)
				.withStyle(ChatFormatting.GRAY), null));
		return lines;
	}

	private static List<Line> nameLines(Settlement settlement) {
		List<Line> lines = new ArrayList<>();
		lines.add(new Line(Component.literal("поселение").withStyle(ChatFormatting.GOLD), null));
		lines.add(new Line(Component.literal(safe(settlement.displayName(), 13)).withStyle(ChatFormatting.WHITE), null));
		lines.add(new Line(Component.literal("казна " + settlement.treasury + "м").withStyle(ChatFormatting.YELLOW), null));
		lines.add(new Line(Component.literal("стройка " + settlement.buildProgress + "% · домов "
				+ settlement.houses.size()).withStyle(ChatFormatting.GRAY), null));
		return lines;
	}

	/** Что нужно поселению: дефицит склада, спрос NPC и материалы для стройки. */
	private static List<Line> needsLines(Settlement settlement) {
		List<Needs> needs = new ArrayList<>();
		for (Rl2dopConfig.Good good : Rl2dopConfig.goods.values()) {
			int stock = settlement.stock(good.item);
			int wanted = Math.max(0, good.targetStock - stock) + Math.max(0, settlement.demand(good.item));
			if (wanted <= 0) {
				continue;
			}
			needs.add(new Needs(good, wanted, stock <= Math.max(4, good.targetStock / 4)));
		}
		needs.sort(Comparator.comparingInt((Needs n) -> n.priority).reversed());
		DyeColor color = needs.isEmpty() ? DyeColor.GREEN : (needs.get(0).critical ? DyeColor.RED : DyeColor.BLACK);
		List<Line> lines = new ArrayList<>();
		String header = settlement.buildProgress < 100 ? "нужно для стройки" : "нужно поселению";
		lines.add(new Line(Component.literal(header).withStyle(ChatFormatting.GOLD), color));
		if (needs.isEmpty()) {
			lines.add(new Line(Component.literal("склад полон").withStyle(ChatFormatting.GREEN), color));
			lines.add(new Line(Component.literal("неси излишки").withStyle(ChatFormatting.YELLOW), color));
			lines.add(new Line(Component.literal("в скупку").withStyle(ChatFormatting.YELLOW), color));
			return lines;
		}
		for (int i = 0; i < 3 && i < needs.size(); i++) {
			Needs need = needs.get(i);
			Component text = Component.literal("")
					.append(goodName(need.good.item))
					.append(Component.literal(" x" + need.priority)
							.withStyle(need.critical ? ChatFormatting.RED : ChatFormatting.YELLOW));
			lines.add(new Line(text, color));
		}
		return lines;
	}

	/** Почём поселение покупает: три самых дорогих товара. */
	private static List<Line> marketLines(Settlement settlement) {
		List<Rl2dopConfig.Good> goods = new ArrayList<>(Rl2dopConfig.goods.values());
		goods.sort(Comparator.comparingDouble(g -> settlement.price(g.item)));
		List<Line> lines = new ArrayList<>();
		double bonus = Math.max(1.0D, Rl2dopConfig.mayorBuyBonus);
		lines.add(new Line(Component.literal("скупка +" + Math.round((bonus - 1.0D) * 100.0D) + "%")
				.withStyle(ChatFormatting.AQUA), DyeColor.BLUE));
		int shown = 0;
		for (int i = goods.size() - 1; i >= 0 && shown < 3; i--, shown++) {
			Rl2dopConfig.Good good = goods.get(i);
			int price = (int) Math.ceil(MarketMath.sellPrice(settlement, good, 0, 1.0D) * bonus);
			Component text = Component.literal("").append(goodName(good.item))
					.append(Component.literal(" " + price + "м").withStyle(ChatFormatting.WHITE));
			lines.add(new Line(text, DyeColor.BLUE));
		}
		return lines;
	}

	private static List<Line> lines(Settlement settlement, String kind) {
		return switch (kind) {
			case KIND_NAME -> nameLines(settlement);
			case KIND_MARKET -> marketLines(settlement);
			case KIND_JOBS -> jobLines(settlement);
			default -> needsLines(settlement);
		};
	}

	private static void write(SignBlockEntity sign, List<Line> lines) {
		SignText text = new SignText();
		DyeColor color = DyeColor.BLACK;
		for (int i = 0; i < 4; i++) {
			if (i >= lines.size()) {
				text = text.setMessage(i, Component.empty());
				continue;
			}
			Line line = lines.get(i);
			color = line.signColor == null ? color : line.signColor;
			text = text.setMessage(i, line.text);
		}
		text = text.setColor(color);
		if (sign.setText(text, true)) {
			sign.setChanged();
			send(sign);
		}
	}

	/**
	 * Название товара на языке игрока: сначала короткий ключ {@code rl2dop.short.<item>} (он есть в
	 * ru_ru/en_us), если перевода нет — полное название предмета.
	 */
	public static Component goodName(String itemId) {
		ResourceLocation id = ResourceLocation.tryParse(itemId);
		if (id == null) {
			return Component.literal(safe(itemId, 14));
		}
		String path = id.getPath();
		if (Rl2dopConfig.goods.containsKey(itemId)) {
			// для товаров поселения у нас есть короткие русские/английские подписи
			return Component.translatableWithFallback("rl2dop.short." + path, path.replace('_', ' '));
		}
		boolean block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(id);
		return Component.translatable((block ? "block." : "item.") + id.getNamespace() + "." + path);
	}

	private static void send(SignBlockEntity sign) {
		BlockPos pos = sign.getBlockPos();
		var state = sign.getLevel().getBlockState(pos);
		sign.getLevel().sendBlockUpdated(pos, state, state, 3);
	}

	private static String safe(String value, int limit) {
		if (value == null) {
			return "";
		}
		String clean = value.toLowerCase(Locale.ROOT);
		if (clean.length() <= limit) {
			return clean;
		}
		return clean.substring(0, Math.max(1, limit - 1)) + "…";
	}

	/** Строка таблички: готовый компонент + цвет самого знака. */
	private record Line(net.minecraft.network.chat.Component text, DyeColor signColor) {
	}

	private record Needs(Rl2dopConfig.Good good, int priority, boolean critical) {
	}
}
