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
 * Постройки поселения: ратуша, рыночная площадь, лавка, склад и жилые дома.
 *
 * <p>План детерминированный (без рандома). Уровень земли под зданием вычисляется по площадке,
 * пол ставится на верхний твёрдый блок, а там, где земля ниже — достраивается фундамент. Так здание
 * сидит на земле, а не висит в воздухе на неровном рельефе.
 *
 * <p>Блоки ставятся только туда, где воздух, трава, песок, вода или камень: то, что построил игрок
 * или деревня, не ломается, а такой блок просто пропускается.
 */
public final class SettlementBuilds {
	private SettlementBuilds() {
	}

	/** Одна позиция плана; воздух означает «расчистить». */
	public record Entry(BlockPos pos, BlockState state) {
	}

	// габариты ратуши: интерьер 11x9, два этажа
	public static final int HALL_HALF_X = 6;
	public static final int HALL_HALF_Z = 5;
	private static final int WALLS_1 = 4;
	private static final int LOFT = 5;
	private static final int WALLS_2 = 3;
	private static final int ROOF = LOFT + WALLS_2 + 1;
	/** Ларёк восточнее ратуши, склад — западнее. */
	private static final int SHOP_X = 9;
	private static final int STORE_X = -12;
	/** Полоса застройки жилых домов: восточнее лавки, вдоль площади. */
	public static final int HOUSE_X = 13;
	public static final int HOUSE_Z = 4;
	public static final int HOUSE_HALF = 3;

	/** Уровень пола площадки: верхний твёрдый блок по максимуму габаритов. */
	public static int platformLevel(ServerLevel level, BlockPos center) {
		int best = Integer.MIN_VALUE;
		for (int x = -SHOP_X - 4; x <= HOUSE_X + HOUSE_HALF; x++) {
			for (int z = -HALL_HALF_Z - 2; z <= 10; z++) {
				int y = topSolidY(level, center.getX() + x, center.getZ() + z, center.getY());
				if (y > best) {
					best = y;
				}
			}
		}
		return best == Integer.MIN_VALUE ? center.getY() : best;
	}

	/** Ставит точку ратуши на уровень земли, чтобы ничего не висело в воздухе. */
	public static BlockPos snapToGround(ServerLevel level, Settlement settlement) {
		BlockPos hall = settlement.hall();
		int y = platformLevel(level, hall);
		BlockPos snapped = new BlockPos(hall.getX(), y, hall.getZ());
		if (snapped.getY() != hall.getY()) {
			settlement.hallPos = snapped.asLong();
			EconomyState.get(level.getServer()).setDirty();
		}
		return snapped;
	}

	private static int topSolidY(ServerLevel level, int x, int z, int fallbackY) {
		// на выгруженном столбце heightmap врёт: без проверки дом может встать в воздухе
		if (!level.hasChunkAt(new BlockPos(x, fallbackY, z))) {
			return fallbackY;
		}
		int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
		if (y <= level.getMinBuildHeight() + 1 || Math.abs(y - fallbackY) > 24) {
			return fallbackY;
		}
		return y;
	}

	/** Полный план застройки для точки ратуши. */
	public static List<Entry> plan(ServerLevel level, Settlement settlement) {
		BlockPos origin = settlement.hall();
		Plan plan = new Plan();

		// 1) расчистка объёма под здание (только природа)
		for (int x = -SHOP_X - 6; x <= HOUSE_X + HOUSE_HALF + 1; x++) {
			for (int z = -HALL_HALF_Z - 2; z <= 10; z++) {
				for (int dy = 1; dy <= ROOF + 4; dy++) {
					BlockPos pos = origin.offset(x, dy, z);
					if (removable(level, pos)) {
						plan.air(pos);
					}
				}
			}
		}

		// 2) площадка: пол ратуши, фундамент, мостовая площади
		for (int x = -HALL_HALF_X; x <= HALL_HALF_X; x++) {
			for (int z = -HALL_HALF_Z; z <= HALL_HALF_Z; z++) {
				BlockPos pos = origin.offset(x, 0, z);
				plan.set(pos, floor(x, z));
				fillFoundation(level, plan, pos);
			}
		}
		for (int x = -SHOP_X - 3; x <= HOUSE_X + HOUSE_HALF; x++) {
			for (int z = -HALL_HALF_Z - 1; z <= 10; z++) {
				if (Math.abs(x) <= HALL_HALF_X && Math.abs(z) <= HALL_HALF_Z) {
					continue;
				}
				BlockPos pos = origin.offset(x, 0, z);
				if (groundLevel(level, pos)) {
					plan.set(pos, paving(x, z));
					fillFoundation(level, plan, pos);
				}
			}
		}

		// 3) ратуша: два этажа, много света, широкий вход
		walls(plan, origin, 1, WALLS_1, Blocks.OAK_PLANKS.defaultBlockState());
		for (int x = -HALL_HALF_X + 1; x <= HALL_HALF_X - 1; x++) {
			for (int z = -HALL_HALF_Z + 1; z <= HALL_HALF_Z - 1; z++) {
				plan.set(origin.offset(x, LOFT, z), Blocks.OAK_PLANKS.defaultBlockState());
			}
		}
		walls(plan, origin, LOFT + 1, WALLS_2, Blocks.OAK_PLANKS.defaultBlockState());

		// крыша
		for (int x = -HALL_HALF_X - 1; x <= HALL_HALF_X + 1; x++) {
			for (int z = -HALL_HALF_Z - 1; z <= HALL_HALF_Z + 1; z++) {
				boolean edgeX = Math.abs(x) == HALL_HALF_X + 1;
				boolean edgeZ = Math.abs(z) == HALL_HALF_Z + 1;
				if (!edgeX && !edgeZ) {
					plan.set(origin.offset(x, ROOF, z), Blocks.SPRUCE_PLANKS.defaultBlockState());
					continue;
				}
				Direction facing = edgeX
						? (x > 0 ? Direction.WEST : Direction.EAST)
						: (z > 0 ? Direction.NORTH : Direction.SOUTH);
				plan.set(origin.offset(x, ROOF, z), stairs(facing));
			}
		}
		plan.set(origin.offset(0, ROOF + 1, 0), Blocks.SPRUCE_FENCE.defaultBlockState());
		plan.set(origin.offset(0, ROOF + 2, 0), Blocks.BELL.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));

		// вход: три проёма (двери по краям, арка в центре) — зайти можно свободно
		entrance(plan, origin);

		// окна: два ряда на первом этаже, ряд на втором — внутри светло
		for (int dz : new int[]{-HALL_HALF_Z, HALL_HALF_Z}) {
			for (int x = -HALL_HALF_X + 2; x <= HALL_HALF_X - 2; x += 2) {
				if (dz == HALL_HALF_Z && Math.abs(x) <= 1) {
					continue; // двери
				}
				plan.set(origin.offset(x, 2, dz), Blocks.GLASS.defaultBlockState());
				plan.set(origin.offset(x, 3, dz), Blocks.GLASS.defaultBlockState());
				plan.set(origin.offset(x, LOFT + 2, dz), Blocks.GLASS.defaultBlockState());
			}
		}
		for (int dx : new int[]{-HALL_HALF_X, HALL_HALF_X}) {
			for (int z = -HALL_HALF_Z + 2; z <= HALL_HALF_Z - 2; z += 2) {
				plan.set(origin.offset(dx, 2, z), Blocks.GLASS.defaultBlockState());
				plan.set(origin.offset(dx, 3, z), Blocks.GLASS.defaultBlockState());
				plan.set(origin.offset(dx, LOFT + 2, z), Blocks.GLASS.defaultBlockState());
			}
		}

		// свет внутри: морские фонари в полу + висячие лампы под перекрытием
		for (int dx : new int[]{-4, 4}) {
			for (int dz : new int[]{-3, 3}) {
				plan.set(origin.offset(dx, 0, dz), Blocks.SEA_LANTERN.defaultBlockState());
				plan.set(origin.offset(dx, LOFT - 1, dz), Blocks.LANTERN.defaultBlockState()
						.setValue(BlockStateProperties.HANGING, true));
			}
		}
		for (int dx : new int[]{-3, 3}) {
			plan.set(origin.offset(dx, LOFT + WALLS_2 - 1, 0), Blocks.LANTERN.defaultBlockState()
					.setValue(BlockStateProperties.HANGING, true));
		}

		// интерьер: стол совета в центре, мебель у стен, лестница на второй этаж
		councilTable(plan, origin.offset(-1, 1, 1));
		plan.set(origin.offset(3, 1, -HALL_HALF_Z + 1), Blocks.CRAFTING_TABLE.defaultBlockState());
		plan.set(origin.offset(4, 1, -HALL_HALF_Z + 1), Blocks.FURNACE.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
		plan.set(origin.offset(-4, 1, -HALL_HALF_Z + 1), Blocks.CHEST.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
		plan.set(origin.offset(-5, 1, -HALL_HALF_Z + 2), Blocks.BOOKSHELF.defaultBlockState());
		plan.set(origin.offset(-5, 1, -HALL_HALF_Z + 3), Blocks.BOOKSHELF.defaultBlockState());
		plan.set(origin.offset(5, 1, 1), Blocks.BARREL.defaultBlockState());
		plan.set(origin.offset(0, 0, HALL_HALF_Z + 3), Blocks.OAK_SLAB.defaultBlockState());
		for (int i = 0; i < 2; i++) {
			plan.set(origin.offset(4, LOFT + 1 + i, 3 - i * 2), stairs(Direction.NORTH));
			plan.set(origin.offset(5, LOFT + 1 + i, 3 - i * 2), stairs(Direction.NORTH));
		}
		// второй этаж: спальня старосты и архив
		for (int dx = -2; dx <= -1; dx++) {
			plan.set(origin.offset(dx, LOFT + 1, -HALL_HALF_Z + 1), Blocks.BOOKSHELF.defaultBlockState());
		}
		plan.set(origin.offset(2, LOFT + 1, -HALL_HALF_Z + 1), Blocks.CHEST.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));

		// крыльцо
		for (int z = HALL_HALF_Z + 1; z <= HALL_HALF_Z + 2; z++) {
			for (int x = -2; x <= 2; x++) {
				plan.set(origin.offset(x, 0, z), x == 0 || Math.abs(x) == 2
						? Blocks.STONE_BRICKS.defaultBlockState()
						: Blocks.OAK_SLAB.defaultBlockState());
			}
		}

		// 4) площадь: фонари по углам, лавка и склад
		for (int[] corner : new int[][]{{-8, 6}, {8, 6}, {-8, 10}, {8, 10}}) {
			lampPost(plan, origin.offset(corner[0], 1, corner[1]), level);
		}
		stall(plan, origin.offset(SHOP_X, 0, 0), true);
		stall(plan, origin.offset(STORE_X, 0, 0), false);

		// 5) стартовые дома: поселение не начинается с пустого места
		int starter = Math.max(0, Math.min(4, Rl2dopConfig.starterHouses));
		for (int i = 0; i < starter; i++) {
			int side = i % 2 == 0 ? 1 : -1;
			BlockPos base = origin.offset(side * (HOUSE_X + 1), 0, HOUSE_Z + (i / 2) * 8);
			housePlan(plan, level, base);
		}

		return plan.entries;
	}

	/** Дом 7x7: фундамент, стены, окна, дверь, крыша, кровать, сундук, свет. */
	public static void housePlan(Plan plan, ServerLevel level, BlockPos base) {
		int half = HOUSE_HALF;
		for (int x = -half - 1; x <= half + 1; x++) {
			for (int z = -half - 1; z <= half + 1; z++) {
				for (int dy = 1; dy <= 6; dy++) {
					BlockPos pos = base.offset(x, dy, z);
					if (removable(level, pos)) {
						plan.air(pos);
					}
				}
			}
		}
		for (int x = -half; x <= half; x++) {
			for (int z = -half; z <= half; z++) {
				BlockPos pos = base.offset(x, 0, z);
				boolean edge = Math.abs(x) == half || Math.abs(z) == half;
				plan.set(pos, edge ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.OAK_PLANKS.defaultBlockState());
				fillFoundation(level, plan, pos);
			}
		}
		for (int dy = 1; dy <= 3; dy++) {
			for (int x = -half; x <= half; x++) {
				boolean doorway = dy <= 2 && Math.abs(x) <= 1;
				if (!doorway) {
					plan.set(base.offset(x, dy, half), Blocks.OAK_PLANKS.defaultBlockState());
				}
				plan.set(base.offset(x, dy, -half), Blocks.OAK_PLANKS.defaultBlockState());
			}
			for (int z = -half + 1; z <= half - 1; z++) {
				plan.set(base.offset(-half, dy, z), Blocks.OAK_PLANKS.defaultBlockState());
				plan.set(base.offset(half, dy, z), Blocks.OAK_PLANKS.defaultBlockState());
			}
			for (int[] corner : new int[][]{{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
				plan.set(base.offset(corner[0] * half, dy, corner[1] * half), Blocks.SPRUCE_LOG.defaultBlockState());
			}
		}
		door(plan, base.offset(-1, 1, half), Direction.SOUTH);
		// порог и ступень
		plan.set(base.offset(-1, 0, half), Blocks.STONE_BRICKS.defaultBlockState());
		plan.set(base.offset(-1, 0, half + 1), topSlab(Blocks.OAK_SLAB.defaultBlockState()));
		// окна и свет
		plan.set(base.offset(-half, 2, 0), Blocks.GLASS.defaultBlockState());
		plan.set(base.offset(half, 2, 0), Blocks.GLASS.defaultBlockState());
		plan.set(base.offset(0, 2, -half), Blocks.GLASS.defaultBlockState());
		plan.set(base.offset(-2, 1, -half + 1), Blocks.CRAFTING_TABLE.defaultBlockState());
		plan.set(base.offset(2, 1, -half + 1), Blocks.CHEST.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
		plan.set(base.offset(0, 1, 0), Blocks.LANTERN.defaultBlockState());
		plan.set(base.offset(0, 2, 0), Blocks.SPRUCE_FENCE.defaultBlockState());
		// крыша
		for (int x = -half - 1; x <= half + 1; x++) {
			for (int z = -half - 1; z <= half + 1; z++) {
				boolean edgeX = Math.abs(x) == half + 1;
				boolean edgeZ = Math.abs(z) == half + 1;
				if (!edgeX && !edgeZ) {
					plan.set(base.offset(x, 4, z), Blocks.SPRUCE_PLANKS.defaultBlockState());
					continue;
				}
				Direction facing = edgeX
						? (x > 0 ? Direction.WEST : Direction.EAST)
						: (z > 0 ? Direction.NORTH : Direction.SOUTH);
				plan.set(base.offset(x, 4, z), stairs(facing));
			}
		}
		// труба из булыжника
		plan.set(base.offset(-half + 1, 5, -half + 1), Blocks.COBBLESTONE.defaultBlockState());
		plan.set(base.offset(-half + 1, 6, -half + 1), Blocks.COBBLESTONE.defaultBlockState());
	}

	/** Построить дом мгновенно (команда /house_add). */
	public static BlockPos buildHouse(ServerLevel level, Settlement settlement, BlockPos standing) {
		BlockPos hall = settlement.hall();
		// в пределах поселения пол дома берём на уровне ратуши (иначе дом «вразнобой» с площадью
		// и свисает), за пределами — по верхнему твёрдому блоку столбца
		boolean inPlot = Math.abs(standing.getX() - hall.getX()) <= 40
				&& Math.abs(standing.getZ() - hall.getZ()) <= 32;
		int y = inPlot ? hall.getY() : topSolidY(level, standing.getX(), standing.getZ(), standing.getY());
		BlockPos base = new BlockPos(standing.getX(), y, standing.getZ());
		Plan plan = new Plan();
		housePlan(plan, level, base);
		int built = 0;
		for (Entry entry : plan.entries) {
			if (place(level, entry)) {
				built++;
			}
		}
		settlement.addHouse(base);
		EconomyState.get(level.getServer()).setDirty();
		return built > 0 ? base : null;
	}

	/**
	 * Поставить следующий кусок плана.
	 *
	 * @param budget сколько блоков за один вызов (0 = всё сразу)
	 * @return прогресс 0..100
	 */
	public static int build(ServerLevel level, Settlement settlement, int budget) {
		snapToGround(level, settlement);
		BlockPos origin = settlement.hall();
		if (!level.hasChunkAt(origin)) {
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
			if (place(level, plan.get(i))) {
				placed++;
			}
		}
		if (placed > 0) {
			int shortage = consumeBuildMaterials(settlement, placed);
			if (shortage > 0 && settlement.buildProgress - settlement.lastShortageAt >= 10) {
				settlement.lastShortageAt = settlement.buildProgress;
				EconomyState.get(level.getServer()).log("поселению " + settlement.id
						+ " для стройки не хватает материалов (камень, брёвна) — нужды висят на табличках");
			}
		}
		if (settlement.shopPos == Long.MIN_VALUE) {
			// прилавок стоит на настиле y=0, значит торговцу нужно y=+1, иначе он в_block'е
			settlement.setShop(origin.offset(SHOP_X - 2, 1, 1));
		}
		settlement.buildProgress = Math.min(100, end * 100 / total);
		if (settlement.buildProgress >= 100) {
			SignBoards.install(level, settlement);
			registerStarterHouses(settlement);
			rescueBuried(level, settlement);
		}
		return settlement.buildProgress;
	}

	/**
	 * Стройка создаёт реальные потребности: расходует склад и поднимает спрос на материалы.
	 * Так таблички с нуждами появляются с начала стройки, а не «из ниоткуда».
	 *
	 * @return сколько материалов не хватило со склада
	 */
	private static int consumeBuildMaterials(Settlement settlement, int placed) {
		int need = Math.max(1, placed / 5);
		int shortage = 0;
		for (String material : List.of("minecraft:cobblestone", "minecraft:oak_log")) {
			int have = settlement.stock(material);
			int take = Math.min(have, need);
			if (take > 0) {
				settlement.takeStock(material, take);
			} else {
				shortage += need;
			}
			settlement.addDemand(material, take > 0 ? need / 2 : need);
		}
		return shortage;
	}

	/** Построить всё сразу (команды, отладка, CI). */
	public static int buildInstant(ServerLevel level, Settlement settlement) {
		settlement.buildProgress = 0;
		int progress = build(level, settlement, 0);
		SignBoards.install(level, settlement);
		return progress;
	}

	public static boolean pending(Settlement settlement) {
		return Rl2dopConfig.autoBuild && settlement.buildProgress < 100;
	}

	// ---------- безопасные точки для жителей ----------

	/** Блок мешает пройти/стоять? */
	public static boolean blocked(ServerLevel level, BlockPos pos) {
		return !level.hasChunkAt(pos) || level.getBlockState(pos).blocksMotion();
	}

	/** Точка, где житель помещается целиком: поднимаем вверх, пока ноги и голова не станут свободны. */
	public static BlockPos safeStand(ServerLevel level, BlockPos pos) {
		BlockPos p = pos;
		for (int i = 0; i < 5; i++) {
			if (!blocked(level, p) && !blocked(level, p.above())) {
				return p;
			}
			p = p.above();
		}
		return p;
	}

	/**
	 * Стройка не должна закапывать жителей: тех, кто оказался внутри блока, поднимаем наверх.
	 * Иначе Minecraft убивает их «inWall», и поселение теряет людей из-за одного /settle_build.
	 */
	public static int rescueBuried(ServerLevel level, Settlement settlement) {
		int saved = 0;
		for (String raw : new ArrayList<>(settlement.npcs)) {
			net.minecraft.world.entity.Entity entity;
			try {
				entity = level.getEntity(java.util.UUID.fromString(raw));
			} catch (IllegalArgumentException e) {
				continue;
			}
			if (!(entity instanceof com.baraaoi.rl2dop.entity.CitizenEntity citizen)) {
				continue;
			}
			BlockPos feet = citizen.blockPosition();
			if (!blocked(level, feet)) {
				continue;
			}
			BlockPos free = safeStand(level, feet);
			if (blocked(level, free)) {
				free = settlement.hall().offset(0, 1, HALL_HALF_Z + 2);
			}
			citizen.teleportTo(free.getX() + 0.5D, free.getY() + 0.05D, free.getZ() + 0.5D);
			citizen.setHomePos(null);
			saved++;
		}
		if (saved > 0) {
			EconomyState.get(level.getServer()).log("поселение " + settlement.id
					+ ": стройка закопала жителей, спасено: " + saved);
		}
		return saved;
	}

	/** Зарегистрировать в поселении стартовые дома из плана (идемпотентно). */
	private static void registerStarterHouses(Settlement settlement) {
		int starter = Math.max(0, Math.min(4, Rl2dopConfig.starterHouses));
		BlockPos origin = settlement.hall();
		for (int i = 0; i < starter; i++) {
			int side = i % 2 == 0 ? 1 : -1;
			settlement.addHouse(origin.offset(side * (HOUSE_X + 1), 0, HOUSE_Z + (i / 2) * 8));
		}
	}

	/** Таблички нужны стоят внутри зала, если ратуша построена. */
	public static boolean hallStanding(Settlement settlement) {
		return settlement.buildProgress >= 100;
	}

	// ---------- вспомогательное ----------

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
			return false;
		}
		level.setBlockAndUpdate(pos, entry.state());
		return true;
	}

	private static void fillFoundation(ServerLevel level, Plan plan, BlockPos floorPos) {
		int top = topSolidY(level, floorPos.getX(), floorPos.getZ(), floorPos.getY() - 1);
		for (int y = floorPos.getY() - 1; y > top; y--) {
			BlockPos pos = new BlockPos(floorPos.getX(), y, floorPos.getZ());
			if (level.getBlockState(pos).isAir()) {
				plan.set(pos, Blocks.COBBLESTONE.defaultBlockState());
			}
		}
	}

	private static void lampPost(Plan plan, BlockPos base, ServerLevel level) {
		plan.set(base, Blocks.SPRUCE_FENCE.defaultBlockState());
		plan.set(base.above(), Blocks.SPRUCE_FENCE.defaultBlockState());
		plan.set(base.above(2), Blocks.SEA_LANTERN.defaultBlockState());
	}

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

	private static void councilTable(Plan plan, BlockPos corner) {
		for (int dx = 0; dx <= 2; dx++) {
			for (int dz = 0; dz <= 1; dz++) {
				plan.set(corner.offset(dx, 0, dz), Blocks.OAK_FENCE.defaultBlockState());
				plan.set(corner.offset(dx, 1, dz), topSlab(Blocks.OAK_SLAB.defaultBlockState()));
			}
		}
	}

	/** Широкий вход: две двери и открытая арка между ними. */
	private static void entrance(Plan plan, BlockPos origin) {
		int z = HALL_HALF_Z;
		for (int dy = 1; dy <= 2; dy++) {
			plan.air(origin.offset(0, dy, z));
		}
		door(plan, origin.offset(-1, 1, z), Direction.SOUTH);
		door(plan, origin.offset(1, 1, z), Direction.SOUTH);
		// козырёк над входом
		for (int x = -2; x <= 2; x++) {
			plan.set(origin.offset(x, 4, z + 1), Blocks.SPRUCE_SLAB.defaultBlockState());
		}
		for (int[] post : new int[][]{{-2, z + 1}, {2, z + 1}}) {
			for (int dy = 1; dy <= 3; dy++) {
				plan.set(origin.offset(post[0], dy, post[1]), Blocks.OAK_FENCE.defaultBlockState());
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

	private static BlockState paving(int x, int z) {
		int ring = Math.max(Math.abs(x), Math.abs(z));
		if (ring % 7 == 0) {
			return Blocks.GRAVEL.defaultBlockState();
		}
		return Blocks.DIRT_PATH.defaultBlockState();
	}

	/** В 1.21.1 у slab одно свойство type (bottom/top/double) — DOUBLE_BLOCK_HALF к нему неприменим. */
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
				// задняя стена сплошная, во фронтальной — проём 3 блока под двери и арку
				plan.set(origin.offset(x, y, -HALL_HALF_Z), wall);
				boolean doorway = y <= 2 && Math.abs(x) <= 1;
				if (!doorway) {
					plan.set(origin.offset(x, y, HALL_HALF_Z), wall);
				}
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

	private static boolean groundLevel(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state.canBeReplaced() || naturalOnly(state);
	}

	private static boolean removable(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state.canBeReplaced() || naturalOnly(state);
	}

	private static boolean naturalOnly(BlockState state) {
		return state.is(BlockTags.DIRT)
				|| state.is(BlockTags.SAND)
				|| state.is(BlockTags.LEAVES)
				|| state.is(BlockTags.LOGS)
				|| state.is(Blocks.GRAVEL)
				|| state.is(Blocks.STONE)
				|| state.is(Blocks.COBBLESTONE)
				|| state.is(Blocks.DEEPSLATE)
				|| state.is(Blocks.COBBLED_DEEPSLATE)
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
