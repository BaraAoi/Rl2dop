package com.baraaoi.rl2dop.economy;

import com.baraaoi.rl2dop.Rl2dopConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.ArrayList;
import java.util.List;

/**
 * Постройки поселения: ратуша, рыночная площадь, лавка и склад.
 *
 * <p>План детерминированный (без рандома), а ставится только то, под чем воздух или «природный»
 * блок: то, что построил игрок или деревня, не ломается — такой блок просто пропускается.
 * Прогресс 0..100 даёт расти здание за несколько тактов экономики, а не одним тяжёлым тиком.
 */
public final class SettlementBuilds {
	private SettlementBuilds() {
	}

	/** Одна позиция плана; воздух означает «расчистить». */
	public record Entry(BlockPos pos, BlockState state) {
	}

	public static final int HALL_HALF_X = 4;
	public static final int HALL_HALF_Z = 3;
	private static final int WALLS_1 = 3;
	private static final int LOFT = 4;
	private static final int WALLS_2 = 2;
	private static final int ROOF = LOFT + WALLS_2;
	/** Ларёк стоит восточнее ратуши; склад — симметрично с западной стороны. */
	private static final int SHOP_X = 6;
	private static final int SHOP_Z = 0;

	/** Полный план застройки для точки ратуши. */
	public static List<Entry> plan(ServerLevel level, Settlement settlement) {
		BlockPos origin = settlement.hall();
		Plan plan = new Plan();

		// расчистка: убираем траву, деревья и воду в габаритах стройки
		for (int x = -SHOP_X - 4; x <= SHOP_X + 3; x++) {
			for (int z = -HALL_HALF_Z - 1; z <= 9; z++) {
				for (int dy = -1; dy <= ROOF + 4; dy++) {
					BlockPos pos = origin.offset(x, dy, z);
					if (removable(level, pos)) {
						plan.air(pos);
					}
				}
			}
		}

		// фундамент и пол
		for (int x = -HALL_HALF_X; x <= HALL_HALF_X; x++) {
			for (int z = -HALL_HALF_Z; z <= HALL_HALF_Z; z++) {
				plan.set(origin.offset(x, -1, z), Blocks.STONE_BRICKS.defaultBlockState());
				plan.set(origin.offset(x, 0, z), floor(x, z));
			}
		}

		// первый этаж, перекрытие, второй этаж
		walls(plan, origin, 1, WALLS_1, Blocks.OAK_PLANKS.defaultBlockState());
		for (int x = -HALL_HALF_X + 1; x <= HALL_HALF_X - 1; x++) {
			for (int z = -HALL_HALF_Z + 1; z <= HALL_HALF_Z - 1; z++) {
				plan.set(origin.offset(x, LOFT, z), Blocks.OAK_PLANKS.defaultBlockState());
			}
		}
		walls(plan, origin, LOFT + 1, WALLS_2, Blocks.OAK_PLANKS.defaultBlockState());

		// крыша: ступени по периметру, доски внутри, конёк и колокол
		for (int x = -HALL_HALF_X - 1; x <= HALL_HALF_X + 1; x++) {
			for (int z = -HALL_HALF_Z - 1; z <= HALL_HALF_Z + 1; z++) {
				boolean edgeX = Math.abs(x) == HALL_HALF_X + 1;
				boolean edgeZ = Math.abs(z) == HALL_HALF_Z + 1;
				if (!edgeX && !edgeZ) {
					plan.set(origin.offset(x, ROOF + 1, z), Blocks.SPRUCE_PLANKS.defaultBlockState());
					continue;
				}
				Direction facing = edgeX
						? (x > 0 ? Direction.WEST : Direction.EAST)
						: (z > 0 ? Direction.NORTH : Direction.SOUTH);
				plan.set(origin.offset(x, ROOF + 1, z), stairs(facing));
			}
		}
		plan.set(origin.offset(0, ROOF + 2, 0), Blocks.SPRUCE_FENCE.defaultBlockState());
		plan.set(origin.offset(0, ROOF + 3, 0), Blocks.BELL.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));

		// вход, окна, лестница на второй этаж
		door(plan, origin.offset(0, 1, HALL_HALF_Z), Direction.SOUTH);
		for (int dz : new int[]{-HALL_HALF_Z, HALL_HALF_Z}) {
			for (int x : new int[]{-2, 2}) {
				plan.set(origin.offset(x, 2, dz), Blocks.GLASS_PANE.defaultBlockState());
				plan.set(origin.offset(x, LOFT + 1, dz), Blocks.GLASS_PANE.defaultBlockState());
			}
		}
		for (int dx : new int[]{-HALL_HALF_X, HALL_HALF_X}) {
			for (int z : new int[]{-1, 1}) {
				plan.set(origin.offset(dx, 2, z), Blocks.GLASS_PANE.defaultBlockState());
				plan.set(origin.offset(dx, LOFT + 1, z), Blocks.GLASS_PANE.defaultBlockState());
			}
		}
		for (int i = 0; i < WALLS_2; i++) {
			plan.set(origin.offset(-3, LOFT + 1 + i, -1), stairs(Direction.NORTH));
		}

		// интерьер: стол совета, сундук казны, книжные полки, верстак и ткани
		table(plan, origin.offset(0, 1, 0));
		plan.set(origin.offset(3, 1, -2), Blocks.CHEST.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
		plan.set(origin.offset(3, 1, -1), Blocks.BOOKSHELF.defaultBlockState());
		plan.set(origin.offset(2, 1, -1), Blocks.BOOKSHELF.defaultBlockState());
		plan.set(origin.offset(-3, 1, -2), Blocks.CRAFTING_TABLE.defaultBlockState());
		plan.set(origin.offset(-3, 1, 2), Blocks.LOOM.defaultBlockState());
		plan.set(origin.offset(-1, 1, -2), Blocks.LANTERN.defaultBlockState());
		plan.set(origin.offset(1, LOFT + 1, -2), Blocks.LANTERN.defaultBlockState());

		// крыльцо
		for (int z = HALL_HALF_Z + 1; z <= HALL_HALF_Z + 2; z++) {
			plan.set(origin.offset(-1, 0, z), Blocks.OAK_SLAB.defaultBlockState());
			plan.set(origin.offset(0, 0, z), Blocks.STONE_BRICKS.defaultBlockState());
			plan.set(origin.offset(1, 0, z), Blocks.OAK_SLAB.defaultBlockState());
		}

		// рыночная площадь: тропа и фонари
		for (int x = -6; x <= 6; x++) {
			for (int z = HALL_HALF_Z + 3; z <= 9; z++) {
				BlockPos pos = origin.offset(x, 0, z);
				if (groundLevel(level, pos)) {
					plan.set(pos, Blocks.DIRT_PATH.defaultBlockState());
				}
			}
		}
		for (int[] corner : new int[][]{{-6, 6}, {6, 6}, {-6, 9}, {6, 9}}) {
			lampPost(plan, origin.offset(corner[0], 1, corner[1]));
		}

		// лавка (восток) и склад (запад)
		stall(plan, origin.offset(SHOP_X, 0, SHOP_Z), true);
		stall(plan, origin.offset(-SHOP_X - 2, 0, SHOP_Z), false);

		SignBoards.planPosts(plan, origin);
		return plan.entries;
	}

	/**
	 * Поставить следующий кусок плана.
	 *
	 * @param budget сколько блоков за один вызов (0 = всё сразу)
	 * @return прогресс 0..100
	 */
	public static int build(ServerLevel level, Settlement settlement, int budget) {
		BlockPos origin = settlement.hall();
		if (!level.hasChunkAt(origin)) {
			// чанки не загружены — не тратим прогресс, попробуем в следующий такт
			return settlement.buildProgress;
		}
		List<Entry> plan = plan(level, settlement);
		int total = plan.size();
		if (total == 0) {
			settlement.buildProgress = 100;
			return 100;
		}
		int start = Math.min(total, settlement.buildProgress * total / 100);
		int end = budget <= 0 ? total : Math.min(total, start + budget);
		int placed = 0;
		for (int i = start; i < end; i++) {
			placed += place(level, plan.get(i)) ? 1 : 0;
		}
		if (settlement.shopPos == Long.MIN_VALUE) {
			// точка входа в магазин — у прилавка лавки
			settlement.setShop(origin.offset(SHOP_X - 1, 0, SHOP_Z));
		}
		settlement.buildProgress = Math.min(100, end * 100 / total);
		if (settlement.buildProgress >= 100) {
			SignBoards.install(level, settlement);
		}
		return settlement.buildProgress;
	}

	/** Построить всё сразу (команды, отладка, CI). */
	public static int buildInstant(ServerLevel level, Settlement settlement) {
		settlement.buildProgress = 0;
		int progress = build(level, settlement, 0);
		SignBoards.install(level, settlement);
		return progress;
	}

	/** Есть ли смысл строит: включён ли автострой и не достроено ли. */
	public static boolean pending(Settlement settlement) {
		return Rl2dopConfig.autoBuild && settlement.buildProgress < 100;
	}

	private static boolean place(ServerLevel level, Entry entry) {
		BlockPos pos = entry.pos();
		if (!level.hasChunkAt(pos)) {
			return false;
		}
		BlockState current = level.getBlockState(pos);
		if (entry.state().isAir()) {
			if (!current.isAir() && removable(level, pos)) {
				level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
				return true;
			}
			return false;
		}
		if (current.equals(entry.state())) {
			return false;
		}
		if (!(current.isAir() || current.canBeReplaced() || naturalOnly(current))) {
			// чужая постройка — не трогаем
			return false;
		}
		level.setBlockAndUpdate(pos, entry.state());
		return true;
	}

	private static void lampPost(Plan plan, BlockPos base) {
		plan.set(base, Blocks.SPRUCE_FENCE.defaultBlockState());
		plan.set(base.above(), Blocks.SPRUCE_FENCE.defaultBlockState());
		plan.set(base.above(2), Blocks.SEA_LANTERN.defaultBlockState());
	}

	/** Ларёк 3x2: прилавок, ящики, навес на столбах. */
	private static void stall(Plan plan, BlockPos base, boolean shop) {
		for (int dx = 0; dx < 3; dx++) {
			for (int dz = 0; dz < 2; dz++) {
				plan.set(base.offset(dx, 0, dz), shop
						? Blocks.OAK_PLANKS.defaultBlockState()
						: Blocks.COBBLED_DEEPSLATE.defaultBlockState());
				BlockPos top = base.offset(dx, 1, dz);
				if (shop && dz == 0) {
					plan.set(top, topSlab(Blocks.OAK_SLAB.defaultBlockState()));
				} else if (dx != 1) {
					plan.set(top, shop ? Blocks.BARREL.defaultBlockState()
							: Blocks.CHEST.defaultBlockState()
							.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
				}
			}
		}
		for (int dx = -1; dx <= 3; dx++) {
			for (int dz = -1; dz <= 2; dz++) {
				plan.set(base.offset(dx, 3, dz), shop
						? Blocks.RED_WOOL.defaultBlockState()
						: Blocks.SPRUCE_SLAB.defaultBlockState());
			}
		}
		for (int[] post : new int[][]{{-1, -1}, {3, -1}, {-1, 2}, {3, 2}}) {
			for (int dy = 1; dy <= 2; dy++) {
				plan.set(base.offset(post[0], dy, post[1]), Blocks.OAK_FENCE.defaultBlockState());
			}
		}
		if (shop) {
			plan.set(base.offset(1, 2, 0), Blocks.LANTERN.defaultBlockState());
			plan.set(base.offset(1, 2, -1), Blocks.GLASS_PANE.defaultBlockState());
		}
	}

	private static void table(Plan plan, BlockPos center) {
		for (int dx = 0; dx <= 1; dx++) {
			for (int dz = 0; dz <= 1; dz++) {
				plan.set(center.offset(dx, 0, dz), Blocks.OAK_FENCE.defaultBlockState());
				plan.set(center.offset(dx, 1, dz), topSlab(Blocks.OAK_SLAB.defaultBlockState()));
			}
		}
	}

	private static BlockState floor(int x, int z) {
		boolean edge = Math.abs(x) == HALL_HALF_X || Math.abs(z) == HALL_HALF_Z;
		if (edge) {
			return Blocks.POLISHED_ANDESITE.defaultBlockState();
		}
		return (x + z) % 2 == 0 ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.SPRUCE_PLANKS.defaultBlockState();
	}

	/** В 1.21.1 у slab одно свойство `type` (bottom/top/double) — DOUBLE_BLOCK_HALF к нему не применять. */
	private static BlockState topSlab(BlockState state) {
		return state.setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP);
	}

	private static BlockState stairs(Direction facing) {
		return Blocks.SPRUCE_STAIRS.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
				.setValue(BlockStateProperties.HALF, Half.TOP);
	}

	private static void walls(Plan plan, BlockPos origin, int startY, int height, BlockState wall) {
		for (int dy = 0; dy < height; dy++) {
			int y = startY + dy;
			for (int x = -HALL_HALF_X; x <= HALL_HALF_X; x++) {
				plan.set(origin.offset(x, y, -HALL_HALF_Z), wall);
				plan.set(origin.offset(x, y, HALL_HALF_Z), wall);
			}
			for (int z = -HALL_HALF_Z + 1; z <= HALL_HALF_Z - 1; z++) {
				plan.set(origin.offset(-HALL_HALF_X, y, z), wall);
				plan.set(origin.offset(HALL_HALF_X, y, z), wall);
			}
			for (int[] corner : new int[][]{{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
				plan.set(origin.offset(corner[0] * HALL_HALF_X, y, corner[1] * HALL_HALF_Z),
						Blocks.STRIPPED_OAK_LOG.defaultBlockState());
			}
		}
	}

	private static void door(Plan plan, BlockPos bottom, Direction facing) {
		plan.set(bottom, Blocks.OAK_DOOR.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
				.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
		plan.set(bottom.above(), Blocks.OAK_DOOR.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
				.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
	}

	/** Годится ли блок под мостовую: он природный или заменяемый. */
	private static boolean groundLevel(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state.canBeReplaced() || naturalOnly(state);
	}

	private static boolean removable(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state.canBeReplaced() || naturalOnly(state);
	}

	/** Природные блоки, которые можно спокойно срезать. Постройки — нет. */
	private static boolean naturalOnly(BlockState state) {
		return state.is(BlockTags.DIRT)
				|| state.is(BlockTags.SAND)
				|| state.is(BlockTags.LEAVES)
				|| state.is(BlockTags.LOGS)
				|| state.is(Blocks.GRAVEL)
				|| state.is(Blocks.STONE)
				|| state.is(Blocks.COBBLESTONE)
				|| state.is(Blocks.DEEPSLATE)
				|| state.is(Blocks.ANDESITE)
				|| state.is(Blocks.DIORITE)
				|| state.is(Blocks.GRANITE)
				|| state.is(Blocks.TUFF)
				|| state.is(Blocks.CALCITE)
				|| state.is(Blocks.MOSS_BLOCK)
				|| state.is(Blocks.WATER)
				|| state.is(Blocks.SNOW)
				|| state.is(Blocks.ICE)
				|| state.is(Blocks.CLAY)
				|| state.is(Blocks.MUD);
	}

	/** Черновик плана. */
	public static final class Plan {
		public final List<Entry> entries = new ArrayList<>();

		public void set(BlockPos pos, BlockState state) {
			if (state != null) {
				entries.add(new Entry(pos, state));
			}
		}

		public void air(BlockPos pos) {
			entries.add(new Entry(pos, Blocks.AIR.defaultBlockState()));
		}
	}
}
