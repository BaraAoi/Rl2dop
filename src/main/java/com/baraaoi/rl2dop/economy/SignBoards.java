package com.baraaoi.rl2dop.economy;

import com.baraaoi.rl2dop.Rl2dopConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
 * Настоящие таблички у ратуши: имя поселения, нужды и цены. Как в Millénaire — игрок видит
 * список прямо на знаках, без команд. Текст пишется на сервере в {@code SignBlockEntity},
 * поэтому его видят все клиенты и он переживает перезагрузку чанка.
 */
public final class SignBoards {
	private SignBoards() {
	}

	public static final String KIND_NAME = "name";
	public static final String KIND_NEEDS = "needs";
	public static final String KIND_MARKET = "market";

	private static final int SPOT_X_NAME = 0;
	private static final int SPOT_X_NEEDS = -2;
	private static final int SPOT_X_MARKET = 2;
	private static final int SPOT_Z = 7;
	private static final int LINE_LIMIT = 14;

	/** Стойки под таблички — часть плана застройки, чтобы знаки росли вместе с ратушей. */
	public static void planPosts(SettlementBuilds.Plan plan, BlockPos origin) {
		for (int x : new int[]{SPOT_X_NEEDS, SPOT_X_NAME, SPOT_X_MARKET}) {
			plan.set(origin.offset(x, 1, SPOT_Z), Blocks.OAK_FENCE.defaultBlockState());
			plan.set(origin.offset(x, 2, SPOT_Z), sign());
		}
	}

	private static BlockState sign() {
		// стоящий знак на столбе: читается с любой стороны и не требует стены
		return Blocks.OAK_SIGN.defaultBlockState().setValue(BlockStateProperties.ROTATION_16, 8);
	}

	/** Поставить знаки сразу (команды/отладка) и запомнить их координаты. */
	public static void install(ServerLevel level, Settlement settlement) {
		BlockPos origin = settlement.hall();
		for (int[] spot : new int[][]{{SPOT_X_NEEDS, 1}, {SPOT_X_NAME, 2}, {SPOT_X_MARKET, 3}}) {
			BlockPos post = origin.offset(spot[0], 1, SPOT_Z);
			BlockPos signPos = origin.offset(spot[0], 2, SPOT_Z);
			if (level.hasChunkAt(post) && level.getBlockState(post).isAir()) {
				level.setBlockAndUpdate(post, Blocks.OAK_FENCE.defaultBlockState());
			}
			if (level.hasChunkAt(signPos) && !level.getBlockState(signPos).is(Blocks.OAK_SIGN)) {
				level.setBlockAndUpdate(signPos, sign());
			}
			String kind = spot[1] == 1 ? KIND_NEEDS : (spot[1] == 2 ? KIND_NAME : KIND_MARKET);
			record(settlement, signPos, kind);
		}
		refreshAll(level.getServer());
	}

	private static void record(Settlement settlement, BlockPos pos, String kind) {
		String entry = pos.getX() + ";" + pos.getY() + ";" + pos.getZ() + ";" + kind;
		if (!settlement.boards.contains(entry)) {
			settlement.boards.add(entry);
		}
	}

	/** Обновить текст всех табличек (раз в displayRefreshTicks). */
	public static void refreshAll(MinecraftServer server) {
		EconomyState state = EconomyState.get(server);
		for (Settlement settlement : state.all()) {
			ServerLevel level = Displays.levelOf(server, settlement.dimension);
			if (level != null) {
				refresh(level, settlement);
			}
		}
	}

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

	private static List<Line> lines(Settlement settlement, String kind) {
		return switch (kind) {
			case KIND_NAME -> nameLines(settlement);
			case KIND_MARKET -> marketLines(settlement);
			default -> needsLines(settlement);
		};
	}

	private static List<Line> nameLines(Settlement settlement) {
		List<Line> lines = new ArrayList<>();
		lines.add(new Line("поселение", ChatFormatting.GOLD, null));
		lines.add(new Line(shorten(settlement.displayName(), 12), ChatFormatting.WHITE, null));
		lines.add(new Line("казна " + settlement.treasury + "м", ChatFormatting.YELLOW, null));
		lines.add(new Line("стр. " + settlement.buildProgress + "% / " + settlement.npcs.size() + " нпс",
				ChatFormatting.GRAY, null));
		return lines;
	}

	/** Что нужно поселению: дефицит склада плюс спрос NPC. */
	private static List<Line> needsLines(Settlement settlement) {
		List<Line> lines = new ArrayList<>();
		List<Needs> needs = new ArrayList<>();
		for (Rl2dopConfig.Good good : Rl2dopConfig.goods.values()) {
			int stock = settlement.stock(good.item);
			int wanted = Math.max(0, good.targetStock - stock) + Math.max(0, settlement.demand(good.item));
			if (wanted <= 0) {
				continue;
			}
			needs.add(new Needs(EconomyState.shortName(good.item), wanted,
					stock <= Math.max(4, good.targetStock / 4)));
		}
		needs.sort(Comparator.comparingInt((Needs n) -> n.priority).reversed());
		DyeColor color = needs.isEmpty() ? DyeColor.GREEN : (needs.get(0).critical ? DyeColor.RED : DyeColor.BLACK);
		lines.add(new Line("нужно", ChatFormatting.GOLD, color));
		if (needs.isEmpty()) {
			lines.add(new Line("склад полон", ChatFormatting.GREEN, color));
		}
		for (int i = 0; i < 3; i++) {
			if (i >= needs.size()) {
				break;
			}
			Needs need = needs.get(i);
			lines.add(new Line(shorten(need.name, 8) + " x" + need.priority,
					need.critical ? ChatFormatting.RED : ChatFormatting.YELLOW, color));
		}
		return lines;
	}

	/** Почём продавать поселению и почём оно продаёт тебе. */
	private static List<Line> marketLines(Settlement settlement) {
		List<Line> lines = new ArrayList<>();
		List<Rl2dopConfig.Good> goods = new ArrayList<>(Rl2dopConfig.goods.values());
		goods.sort(Comparator.comparingDouble(g -> settlement.price(g.item)));
		lines.add(new Line("скупка", ChatFormatting.AQUA, DyeColor.BLUE));
		for (int i = goods.size() - 1, shown = 0; i >= 0 && shown < 3; i--, shown++) {
			Rl2dopConfig.Good good = goods.get(i);
			lines.add(new Line(shorten(EconomyState.shortName(good.item), 8) + " "
							+ MarketMath.sellPrice(settlement, good, 0, 1.0D) + "м",
					ChatFormatting.WHITE, DyeColor.BLUE));
		}
		return lines;
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
			text = text.setMessage(i, line.component());
		}
		text = text.setColor(color);
		if (sign.setText(text, true)) {
			sign.setChanged();
			LevelHelper.send(sign);
		}
	}

	private static String shorten(String value, int limit) {
		if (value == null) {
			return "";
		}
		String clean = value.toLowerCase(Locale.ROOT);
		return clean.length() <= limit ? clean : clean.substring(0, Math.max(1, limit - 1)) + "…";
	}

	/** Строка таблички: текст, цвет текста, цвет знака. */
	private record Line(String text, ChatFormatting style, DyeColor signColor) {
		Component component() {
			return Component.literal(text).withStyle(style);
		}
	}

	private record Needs(String name, int priority, boolean critical) {
	}

	/** Помощник, чтобы не тянуть зависимости в других местах. */
	private static final class LevelHelper {
		static void send(SignBlockEntity sign) {
			BlockPos pos = sign.getBlockPos();
			BlockState state = sign.getLevel().getBlockState(pos);
			sign.getLevel().sendBlockUpdated(pos, state, state, 3);
		}
	}
}
