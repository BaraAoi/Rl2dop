package com.baraaoi.rl2dop.economy;

import com.baraaoi.rl2dop.Rl2dopConfig;
import com.baraaoi.rl2dop.entity.Profession;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Постройки поселения: ратуша, площадь с колодцем, лавка, склад, жилые участки, таверна с прудом
 * и рабочие мастерские.
 *
 * <p>План детерминированный (без рандома) и спланирован как ванильная деревня: ратуша в центре
 * мощёной площади, две улицы с поперечными, переулок, десять жилых участков вдоль улиц, таверна с
 * прудом во дворе и ряд мастерских за северной улицей. К каждой двери ведёт дорожка.
 *
 * <p>Рельеф не «выпрямляется силой»: `Ground.pick()` для каждой постройки ищет ровное место рядом
 * с точкой плана и берёт уровень этого места, `Ground.grade()` ровняет только саму площадку
 * (плюс откос в 1 блок по краю), а `Ground.path()` кладёт улицы лентами, которые идут по грунту и
 * меняют высоту не больше чем на блок за шаг. Поэтому посёлок не выглядит карьером ни на равнине,
 * ни на склоне, а низ под площадками добивается фундаментом — ничего не висит в воздухе.
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

	// ---------- планировка деревни (как в ванильной: улица, участки вдоль неё, двор ратуши) ----------

	/** Половина площади по X/Z: всё внутри мощёное и выровненное. */
	public static final int PLAZA_HALF_X = 18;
	public static final int PLAZA_HALF_Z = 21;
	/** Главная улица (южная, вдоль неё жилые дома) и рабочая улица (северная, за ней мастерские). */
	public static final int STREET_SOUTH_Z = 16;
	public static final int STREET_NORTH_Z = -24;
	/** Продольные улицы идут от -ROAD_X до +ROAD_X, поперечные замыкают их с двух сторон. */
	public static final int ROAD_X = 46;
	public static final int ROAD_HALF = 1;
	/** Задний переулок для второго ряда домов. */
	public static final int LANE_Z = 30;
	/** Ряды жилых участков: первый вдоль главной улицы, второй — за переулком. */
	public static final int[] PLOT_ROW_Z = {8, 22};
	public static final int[][] PLOT_ROW_X = {{-44, -32, -20, 20, 32, 44}, {-38, -26, 26, 38}};
	/** Таверна у пруда: оба стоят в дворе ратуши с северной стороны, окнами/дверью на площадь. */
	public static final int TAVERN_X = -13;
	public static final int TAVERN_Z = -15;
	public static final int POND_X = 11;
	public static final int POND_Z = -15;
	/** Ряд мастерских за рабочей улицей. */
	public static final int WORK_ROW_Z = -34;
	public static final int[] WORK_X_FARM = {-30};
	public static final int WORK_STEP_X = 20;
	/** Насколько улица может отойти от уровня площади, подстраиваясь под рельеф (в блоках). */
	private static final int ROAD_TILT = 4;

	/**
	 * Уровень пола: верхний блок грунта под точкой ратуши. Мерим именно природный/чужой блок,
	 * а не максимум по пятну застройки — иначе собственные крыши поднимали бы поселение на
	 * несколько блоков вверх при каждой перестройке. Неровности закрывает fillFoundation().
	 */
	public static int platformLevel(ServerLevel level, BlockPos center) {
		return groundTopY(level, center.getX(), center.getZ(), center.getY());
	}

	/** Первый непроходимый блок, начиная с given y и вниз (16 попыток). */
	private static int groundTopY(ServerLevel level, int x, int z, int fromY) {
		if (!level.hasChunkAt(new BlockPos(x, fromY, z))) {
			return fromY;
		}
		int y = fromY;
		for (int i = 0; i < 16; i++) {
			BlockState state = level.getBlockState(new BlockPos(x, y, z));
			if (!state.canBeReplaced()) {
				return y;
			}
			if (y <= level.getMinBuildHeight() + 1) {
				return fromY;
			}
			y--;
		}
		return fromY;
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

	/**
	 * Место под основание поселения: ищем ровное пятно размером с площадку ратуши в окрестности
	 * запрошенной точки (шаг 3 блока, радиус ±18) и берём то, где меньше всего земляных работ.
	 * Так деревня «выбирает», где ей встать, а не получает котлован на холме.
	 *
	 * @return выбранная точка (y — уровень площадки) или исходная, если окрестность не выгружена
	 */
	public static BlockPos chooseSite(ServerLevel level, BlockPos desired, int[] outSpread) {
		if (level == null) {
			return desired;
		}
		int halfX = PLAZA_HALF_X / 2;
		int halfZ = PLAZA_HALF_Z / 2;
		Ground ground = new Ground(level, desired.getY());
		BlockPos site = ground.pick(desired.getX(), desired.getZ(), halfX, halfZ, 6, 3);
		if (site == null) {
			site = desired;
		}
		if (outSpread != null) {
			outSpread[0] = ground.spread(site.getX(), site.getZ(), halfX, halfZ);
		}
		return site;
	}

	/** Перепад грунта над площадкой ратуши — показатель «ровное ли место» (0 = идеально). */
	public static int siteSpread(ServerLevel level, BlockPos center) {
		return level == null ? 0
				: new Ground(level, center.getY()).spread(center.getX(), center.getZ(), PLAZA_HALF_X / 2,
						PLAZA_HALF_Z / 2);
	}

	/**
	 * Проверка качества застройки: у каждой постройки площадка ровная (перепад ≤ 1 блока),
	 * дорога не делает перепад больше блока на шаг, под постройкой есть опора. Нужна потому,
	 * что «красиво» глазами в CI не проверить — проверяем числа, на которых красота держится.
	 *
	 * @return человекочитаемый отчёт; нарушений быть не должно
	 */
	public static String audit(ServerLevel level, Settlement settlement) {
		if (level == null || settlement == null) {
			return "нет поселения";
		}
		BlockPos origin = settlement.hall();
		Ground ground = new Ground(level, origin.getY());
		List<BlockPos> spots = new ArrayList<>();
		spots.add(origin);
		for (String record : settlement.houses) {
			String[] parts = record.split(";");
			if (parts.length != 3) {
				continue;
			}
			try {
				spots.add(new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
						Integer.parseInt(parts[2])));
			} catch (NumberFormatException ignored) {
				// битую запись не считаем
			}
		}
		if (settlement.hasTavern()) {
			spots.add(settlement.tavern());
		}
		for (int i = 0; i < 4; i++) {
			BlockPos base = workBase(level, origin, i);
			BlockPos spot = ground.pick(base.getX(), base.getZ(), 8, 7);
			spots.add(spot != null ? spot : base);
		}
		int pads = 0;
		int uneven = 0;
		int floating = 0;
		for (BlockPos spot : spots) {
			if (spot == null || !level.hasChunkAt(spot)) {
				continue;
			}
			pads++;
			if (ground.floorSpread(spot.getX(), spot.getZ(), spot.getY(), 3) > 1) {
				uneven++;
			}
			BlockPos under = spot.below();
			if (!level.getBlockState(under).isRedstoneConductor(level, under)) {
				floating++;   // под постройкой пустота/несплошной блок — «висит»
			}
		}
		// шаг улицы: перепад соседних колонок мощения (2+ это ещё ступенька, 4+ уже обрыв)
		int roadMaxStep = 0;
		int cells = 0;
		for (int z : new int[]{origin.getZ() + STREET_SOUTH_Z, origin.getZ() + STREET_NORTH_Z}) {
			int prev = ground.level(origin.getX() - ROAD_X, z);
			for (int x = origin.getX() - ROAD_X + 1; x <= origin.getX() + ROAD_X; x++) {
				int now = ground.level(x, z);
				roadMaxStep = Math.max(roadMaxStep, Math.abs(now - prev));
				prev = now;
				cells++;
			}
		}
		int violations = uneven + floating + (roadMaxStep > 3 ? 1 : 0);
		return "площадок " + pads + ", кривых " + uneven + ", висячих " + floating
				+ " · шагов дороги " + cells + ", макс перепад " + roadMaxStep + " · нарушений " + violations;
	}

	/**
	 * Точку работы профессии привязываем один раз: без этого уровень площадки при каждой
	 * перестройке считался бы уже по крыше готовой мастерской, и координаты «прыгали» бы на
	 * несколько блоков между запусками.
	 */
	private static void site(Settlement settlement, Profession profession, BlockPos pos) {
		if (!settlement.workSites.containsKey(profession.id())) {
			settlement.setWorkSite(profession, pos);
		}
	}

	/**
	 * Полный план застройки: площадь, ратуша, улицы, жилые участки, таверна с прудом и мастерские.
	 * Всё выравнивается под один уровень (террасы), поэтому поселение одинаково хорошо выглядит и на
	 * равнине, и на склоне.
	 */
	public static List<Entry> plan(ServerLevel level, Settlement settlement) {
		BlockPos origin = settlement.hall();
		Plan plan = new Plan();
		Ground ground = new Ground(level, origin.getY());

		// 1) ПЛОЩАДКА ТОЛЬКО вокруг ратуши (крыльцо + колодец): ровный пятачок, где стоит главное
		//    здание. Раньше выравнивался весь квадрат площади 37x43 — на холме это выглядело
		//    карьером. Теперь рельеф остаётся рельефом, а к нему притираются и дома, и дороги.
		ground.grade(plan, origin.getX(), origin.getZ(), PLAZA_HALF_X / 2, PLAZA_HALF_Z / 2, origin.getY(),
				Blocks.DIRT_PATH.defaultBlockState(), Blocks.DIRT.defaultBlockState());
		for (int x = -SHOP_X - 6; x <= HOUSE_X + HOUSE_HALF + 1; x++) {
			for (int z = -HALL_HALF_Z - 2; z <= HALL_HALF_Z + 5; z++) {
				for (int dy = 1; dy <= ROOF + 4; dy++) {
					BlockPos pos = origin.offset(x, dy, z);
					if (removable(level, pos)) {
						plan.air(pos);
					}
				}
			}
		}

		// 2) площадь: пол ратуши + мощение всего внутреннего квадрата (без «если там чисто»)
		for (int x = -HALL_HALF_X; x <= HALL_HALF_X; x++) {
			for (int z = -HALL_HALF_Z; z <= HALL_HALF_Z; z++) {
				BlockPos pos = origin.offset(x, 0, z);
				plan.set(pos, floor(x, z));
				fillFoundation(level, plan, pos);
			}
		}
		for (int x = -PLAZA_HALF_X / 2; x <= PLAZA_HALF_X / 2; x++) {
			for (int z = -PLAZA_HALF_Z / 2; z <= PLAZA_HALF_Z / 2; z++) {
				if (Math.abs(x) <= HALL_HALF_X && Math.abs(z) <= HALL_HALF_Z) {
					continue;
				}
				if (isTavernPlot(origin, x, z) || isPondPlot(origin, x, z)) {
					continue;
				}
				plan.set(origin.offset(x, 0, z), paving(x, z));
			}
		}
		// колодец на площади — как в ванильной деревне (в стороне, чтобы не перекрыть входную дорожку)
		well(plan, origin.offset(5, 0, HALL_HALF_Z + 3));

		// 2а) площадки стартовых домов ГОТОВЯМ ДО дорог: каждая притирается к своему месту,
		//     а дороги потом обходят готовые пятна и приводят к калитке каждого дома
		List<BlockPos> plots = housePlots(level, settlement);
		List<BlockPos> fresh = starterPlots(level, settlement, plots);
		for (BlockPos plot : fresh) {
			gradeHousePad(plan, ground, plot);
		}
		// улицы, переулки и дорожки к дверям
		roads(plan, ground, level, origin, settlement);

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

		// 4) площадь: фонари по углам, лавка и склад, колодец у крыльца
		for (int[] corner : new int[][]{{-PLAZA_HALF_X / 2 + 1, PLAZA_HALF_Z / 2 - 1},
				{PLAZA_HALF_X / 2 - 1, PLAZA_HALF_Z / 2 - 1},
				{-PLAZA_HALF_X / 2 + 1, -PLAZA_HALF_Z / 2 + 1}, {PLAZA_HALF_X / 2 - 1, -PLAZA_HALF_Z / 2 + 1}}) {
			lampPost(plan, origin.offset(corner[0], 1, corner[1]), level);
		}
		stall(plan, origin.offset(SHOP_X, 0, 0), true);
		stall(plan, origin.offset(STORE_X, 0, 0), false);

		// 5) жилые дома: каждый на своей площадке, вдоль улицы, с дорожкой к калитке
		for (BlockPos plot : fresh) {
			houseOnPlot(plan, ground, level, plot);
		}

		// 6) таверна с прудом и рабочие постройки
		if (Rl2dopConfig.tavernEnabled) {
			tavernPlan(plan, ground, level, settlement, origin.offset(TAVERN_X, 0, TAVERN_Z));
			if (Rl2dopConfig.villagePond) {
				pondPlan(plan, ground, level, origin.offset(POND_X, 0, POND_Z));
			}
		}
		if (Rl2dopConfig.starterWorkshops) {
			workshops(plan, ground, level, settlement, origin);
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

	// ---------- рабочие постройки поселения ----------

	/** Как далеко от ратуши ставятся ферма/шахта/лесопилка/мастерская. */
	public static final int WORK_X = 26;
	public static final int WORK_Z = 2;
	public static final int WORK_Z_BACK = -14;

	/**
	 * Рабочие постройки, которые поселение получает сразу. Каждая — домик + само производство,
	 * и к ней привязывается рабочая точка профессии ({@code Settlement.workSites}), так что
	 * шахтёру не нужно ничего копать: он стоит у своей шахты и производит камень и руду.
	 */
	private static void workshops(Plan plan, Ground ground, ServerLevel level, Settlement settlement,
				BlockPos origin) {
		farmPlan(plan, level, settlement, workPad(plan, ground, origin, 0, 9, 8));
		lumberPlan(plan, level, settlement, workPad(plan, ground, origin, 1, 8, 7));
		minePlan(plan, level, settlement, workPad(plan, ground, origin, 2, 8, 7));
		builderPlan(plan, level, settlement, workPad(plan, ground, origin, 3, 8, 7));
	}

	/** Площадка мастерской: ровный участок того же уровня, что и ратуша (терраса на склоне). */
	private static BlockPos workPad(Plan plan, Ground ground, BlockPos origin, int index, int halfX, int halfZ) {
		BlockPos center = workBase(null, origin, index);
		BlockPos spot = ground.pick(center.getX(), center.getZ(), halfX, halfZ);
		BlockPos at = spot != null ? spot : center;
		int y = ground.grade(plan, at.getX(), at.getZ(), halfX, halfZ, at.getY(),
				Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState());
		return new BlockPos(at.getX(), y, at.getZ());
	}

	/** Расчистка объёма над пятном постройки (только природное). */
	private static void carve(Plan plan, ServerLevel level, BlockPos base, int halfX, int halfZ, int height) {
		for (int x = -halfX - 1; x <= halfX + 1; x++) {
			for (int z = -halfZ - 1; z <= halfZ + 1; z++) {
				for (int dy = 1; dy <= height; dy++) {
					BlockPos pos = base.offset(x, dy, z);
					if (removable(level, pos)) {
						plan.air(pos);
					}
				}
			}
		}
	}

	/** Компактный домик 5x5: стены, дверь с юга, окно, сундук, свет, крыша. */
	private static void hut(Plan plan, ServerLevel level, BlockPos base, BlockState wall) {
		int half = 2;
		for (int x = -half; x <= half; x++) {
			for (int z = -half; z <= half; z++) {
				BlockPos pos = base.offset(x, 0, z);
				boolean edge = Math.abs(x) == half || Math.abs(z) == half;
				plan.set(pos, edge ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.OAK_PLANKS.defaultBlockState());
				fillFoundation(level, plan, pos);
			}
		}
		for (int dy = 1; dy <= 2; dy++) {
			for (int x = -half; x <= half; x++) {
				boolean doorway = dy <= 2 && Math.abs(x) <= 0;
				if (!doorway) {
					plan.set(base.offset(x, dy, half), wall);
				}
				plan.set(base.offset(x, dy, -half), wall);
			}
			for (int z = -half + 1; z <= half - 1; z++) {
				plan.set(base.offset(-half, dy, z), wall);
				plan.set(base.offset(half, dy, z), wall);
			}
		}
		door(plan, base.offset(0, 1, half), Direction.SOUTH);
		plan.set(base.offset(-half, 2, 0), Blocks.GLASS.defaultBlockState());
		plan.set(base.offset(half, 2, 0), Blocks.GLASS.defaultBlockState());
		plan.set(base.offset(-1, 1, -half + 1), Blocks.CHEST.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
		plan.set(base.offset(1, 1, -half + 1), Blocks.LANTERN.defaultBlockState());
		for (int x = -half - 1; x <= half + 1; x++) {
			for (int z = -half - 1; z <= half + 1; z++) {
				boolean edgeX = Math.abs(x) == half + 1;
				boolean edgeZ = Math.abs(z) == half + 1;
				if (!edgeX && !edgeZ) {
					plan.set(base.offset(x, 3, z), Blocks.SPRUCE_PLANKS.defaultBlockState());
					continue;
				}
				Direction facing = edgeX ? (x > 0 ? Direction.WEST : Direction.EAST)
						: (z > 0 ? Direction.NORTH : Direction.SOUTH);
				plan.set(base.offset(x, 3, z), stairs(facing));
			}
		}
	}

	/** Ферма: грядки со спелой пшеницей, забор, кадка с водой, домик фермера. */
	private static void farmPlan(Plan plan, ServerLevel level, Settlement settlement, BlockPos base) {
		int halfX = 6;
		int halfZ = 4;
		carve(plan, level, base, halfX, halfZ, 4);
		for (int x = -halfX; x <= halfX; x++) {
			for (int z = -halfZ; z <= halfZ; z++) {
				plan.set(base.offset(x, 0, z), Blocks.DIRT.defaultBlockState());
			}
		}
		// канавка с водой по центру: без неё грядки высыхают в землю через минуту
		for (int x = -halfX + 1; x <= halfX - 1; x++) {
			plan.set(base.offset(x, 0, 0), Blocks.WATER.defaultBlockState());
		}
		// грядки в шахматном порядке по обе стороны канавки, сразу спелые
		for (int x = -halfX + 1; x <= halfX - 1; x++) {
			for (int z = -halfZ + 1; z <= halfZ - 1; z += 2) {
				if (z == 0) {
					continue;
				}
				BlockPos soil = base.offset(x, 0, z);
				plan.set(soil, Blocks.FARMLAND.defaultBlockState()
						.setValue(BlockStateProperties.MOISTURE, 7));
				plan.set(soil.above(), Blocks.WHEAT.defaultBlockState()
						.setValue(BlockStateProperties.AGE_7, 7));
			}
		}
		for (int x = -halfX; x <= halfX; x++) {
			plan.set(base.offset(x, 1, -halfZ), Blocks.OAK_FENCE.defaultBlockState());
			if (Math.abs(x) > 1) {
				plan.set(base.offset(x, 1, halfZ), Blocks.OAK_FENCE.defaultBlockState());
			}
		}
		for (int z = -halfZ; z <= halfZ; z++) {
			plan.set(base.offset(-halfX, 1, z), Blocks.OAK_FENCE.defaultBlockState());
			plan.set(base.offset(halfX, 1, z), Blocks.OAK_FENCE.defaultBlockState());
		}
		plan.set(base.offset(halfX - 1, 1, 0), Blocks.WATER_CAULDRON.defaultBlockState());
		plan.set(base.offset(-halfX + 1, 1, halfZ - 1), Blocks.BARREL.defaultBlockState());
		hut(plan, level, base.offset(-halfX + 2, 0, -halfZ - 4), Blocks.OAK_PLANKS.defaultBlockState());
		site(settlement, Profession.FARMER, base.offset(0, 1, 1));
	}

	/** Дом лесоруба: избушка, поленница, пни и саженцы вокруг. */
	private static void lumberPlan(Plan plan, ServerLevel level, Settlement settlement, BlockPos base) {
		carve(plan, level, base, 5, 5, 5);
		hut(plan, level, base, Blocks.STRIPPED_OAK_LOG.defaultBlockState());
		// поленница
		for (int dx = 0; dx < 3; dx++) {
			for (int dz = 0; dz < 2; dz++) {
				plan.set(base.offset(3 + dx, 1, -1 + dz), Blocks.OAK_LOG.defaultBlockState()
						.setValue(BlockStateProperties.AXIS, Direction.Axis.X));
				plan.set(base.offset(3 + dx, 2, -1 + dz), Blocks.OAK_LOG.defaultBlockState()
						.setValue(BlockStateProperties.AXIS, Direction.Axis.X));
			}
		}
		// пни и саженцы: лес рядом с домом
		for (int[] spot : new int[][]{{-4, 4}, {-2, 5}, {2, 5}, {5, 4}, {-5, -3}}) {
			plan.set(base.offset(spot[0], 1, spot[1]), Blocks.OAK_STAIRS.defaultBlockState());
			plan.set(base.offset(spot[0], 2, spot[1]), Blocks.OAK_SAPLING.defaultBlockState());
		}
		plan.set(base.offset(-3, 1, 3), Blocks.CRAFTING_TABLE.defaultBlockState());
		lampPost(plan, base.offset(4, 1, 4), level);
		site(settlement, Profession.LUMBERJACK, base.offset(0, 1, 4));
	}

	/** Шахта: ствол с лестницей, рудные блоки в породе, крепь, свет, бытовка. */
	private static void minePlan(Plan plan, ServerLevel level, Settlement settlement, BlockPos base) {
		carve(plan, level, base, 4, 4, 5);
		// площадка устья
		for (int x = -2; x <= 2; x++) {
			for (int z = -2; z <= 2; z++) {
				plan.set(base.offset(x, 0, z), Blocks.COBBLESTONE.defaultBlockState());
			}
		}
		// ствол вниз: 3x3, крепь из брёвен, лестница
		for (int dy = 1; dy <= 4; dy++) {
			for (int x = -1; x <= 1; x++) {
				for (int z = -1; z <= 1; z++) {
					BlockPos pos = base.offset(x, -dy, z);
					if (x == 0 && z == 0) {
						// ствол чистим и вешаем лестницу (опора с севера есть — там порода)
						plan.set(pos, Blocks.AIR.defaultBlockState());
						if (dy >= 2) {
							plan.set(pos, Blocks.LADDER.defaultBlockState()
									.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
						}
						continue;
					}
					plan.set(pos, dy <= 2 ? Blocks.STONE.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState());
				}
			}
			plan.set(base.offset(0, -dy, 2), Blocks.AIR.defaultBlockState());
		}
		for (int x = -1; x <= 1; x++) {
			plan.set(base.offset(x, 1, -2), Blocks.SPRUCE_LOG.defaultBlockState()
						.setValue(BlockStateProperties.AXIS, Direction.Axis.X));
		}
		plan.set(base.offset(0, 1, -2), Blocks.LANTERN.defaultBlockState());
		// руда в породе вокруг устья — шахтёр её «добывает», ничего копать не надо
		int[][] ores = {{-3, 1}, {3, -1}, {-4, -2}, {4, 2}, {-2, -4}, {2, 4}};
		for (int i = 0; i < ores.length; i++) {
			BlockState ore = switch (i % 3) {
				case 0 -> Blocks.IRON_ORE.defaultBlockState();
				case 1 -> Blocks.COAL_ORE.defaultBlockState();
				default -> Blocks.COPPER_ORE.defaultBlockState();
			};
			plan.set(base.offset(ores[i][0], 0, ores[i][1]), ore);
		}
		// отвал
		for (int dx = 0; dx <= 2; dx++) {
			plan.set(base.offset(dx, 1, 3 + dx / 2), Blocks.COBBLESTONE.defaultBlockState());
		}
		hut(plan, level, base.offset(-4, 0, 4), Blocks.COBBLESTONE.defaultBlockState());
		site(settlement, Profession.MINER, base.offset(0, 1, 1));
	}

	/** Мастерская строителя: станок, леса, штабель кирпича и камня. */
	private static void builderPlan(Plan plan, ServerLevel level, Settlement settlement, BlockPos base) {
		carve(plan, level, base, 5, 4, 6);
		hut(plan, level, base, Blocks.STONE_BRICKS.defaultBlockState());
		plan.set(base.offset(3, 1, 0), Blocks.SMITHING_TABLE.defaultBlockState());
		plan.set(base.offset(3, 1, 2), Blocks.CRAFTING_TABLE.defaultBlockState());
		for (int dy = 1; dy <= 3; dy++) {
			plan.set(base.offset(4, dy, -2), Blocks.SCAFFOLDING.defaultBlockState());
		}
		plan.set(base.offset(4, 4, -2), Blocks.LANTERN.defaultBlockState());
		for (int dx = 0; dx <= 2; dx++) {
			for (int dz = 0; dz <= 1; dz++) {
				plan.set(base.offset(-3 + dx, 1, 3 + dz), dx == 1
						? Blocks.STONE_BRICKS.defaultBlockState()
						: Blocks.COBBLESTONE.defaultBlockState());
			}
		}
		lampPost(plan, base.offset(-4, 1, -3), level);
		site(settlement, Profession.BUILDER, base.offset(0, 1, 4));
	}

	// ---------- деревня: площадки, улицы, участки, таверна ----------

	/**
	 * Площадка жилого участка: выравниваем пятно вокруг ТОЧКИ УЧАСТКА по её же грунту — уровень
	 * выбирает `Ground.pick()`, поэтому дом стоит на ровном, а не на «уровне ратуши любой ценой».
	 */
	private static int gradeHousePad(Plan plan, Ground ground, BlockPos plot) {
		return ground.grade(plan, plot.getX(), plot.getZ(), HOUSE_HALF + 2, HOUSE_HALF + 2, plot.getY(),
				Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState());
	}

	/** Мощность дорог: в основном тропинка, местами гравий и твёрдый грунт — как в ваниле. */
	private static BlockState roadTop(int x, int z) {
		int hash = Math.abs(x * 73856093 ^ z * 19349663);
		if (hash % 11 == 0) {
			return Blocks.GRAVEL.defaultBlockState();
		}
		if (hash % 17 == 0) {
			return Blocks.COARSE_DIRT.defaultBlockState();
		}
		return Blocks.DIRT_PATH.defaultBlockState();
	}

	/**
	 * Сеть дорог. Это не «полосы мощения на весь посёлок», а ленты между точками: каждая идёт по
	 * грунту и меняет высоту максимум на блок за шаг, поэтому улица на склоне — лесенка, а не
	 * терраса и не рампа. Колонки, которые заняли площадки построек, дорога не перетирает: она
	 * подходит к калитке и примыкает к уже готовому уровню.
	 */
	private static void roads(Plan plan, Ground ground, ServerLevel level, BlockPos origin, Settlement settlement) {
		int cx = origin.getX();
		int cz = origin.getZ();
		// главная и рабочая улицы, две поперечные и переулок второго ряда
		ground.path(plan, cx - ROAD_X, cz + STREET_SOUTH_Z, cx + ROAD_X, cz + STREET_SOUTH_Z, ROAD_HALF);
		ground.path(plan, cx - ROAD_X, cz + STREET_NORTH_Z, cx + ROAD_X, cz + STREET_NORTH_Z, ROAD_HALF);
		ground.path(plan, cx - ROAD_X, cz + STREET_NORTH_Z, cx - ROAD_X, cz + LANE_Z, ROAD_HALF);
		ground.path(plan, cx + ROAD_X, cz + STREET_NORTH_Z, cx + ROAD_X, cz + LANE_Z, ROAD_HALF);
		ground.path(plan, cx - ROAD_X, cz + LANE_Z, cx + ROAD_X, cz + LANE_Z, ROAD_HALF);
		// выходы ратуши: на главную улицу и к рабочей
		ground.path(plan, cx, cz + HALL_HALF_Z + 3, cx, cz + STREET_SOUTH_Z, 1);
		ground.path(plan, cx, cz - HALL_HALF_Z - 2, cx, cz + STREET_NORTH_Z, 1);
		// дорожка от калитки каждого участка до его улицы
		for (BlockPos plot : housePlots(level, settlement)) {
			int streetZ = nearestStreetZ(plot.getZ() - cz);
			ground.path(plan, plot.getX(), plot.getZ() + HOUSE_HALF + 3, plot.getX(), cz + streetZ, 0);
		}
		// подвоз материала к мастерским и дорожка вдоль двора таверны
		for (int i = 0; i < 4; i++) {
			int x = cx - 30 + i * WORK_STEP_X;
			ground.path(plan, x, cz + WORK_ROW_Z + 6, x, cz + STREET_NORTH_Z, 0);
		}
		if (Rl2dopConfig.tavernEnabled) {
			ground.path(plan, cx + TAVERN_X + 4, cz + TAVERN_Z, cx + POND_X - 6, cz + TAVERN_Z, 0);
		}
	}

	/** До какой улицы вести дорожку участка (ближайшая по z). */
	private static int nearestStreetZ(int plotZRel) {
		if (plotZRel >= (LANE_Z + STREET_SOUTH_Z) / 2) {
			return LANE_Z;
		}
		if (plotZRel >= (STREET_SOUTH_Z + STREET_NORTH_Z) / 2) {
			return STREET_SOUTH_Z;
		}
		return STREET_NORTH_Z;
	}

	/**
	 * Участки под стартовые дома: первые N СВОБОДНЫХ (там, где дома ещё нет). Благодаря этому
	 * `reset` и `settle_regrade` не плодят вторые дома на тех же местах — план пересчитывается,
	 * а застраивается только то, что ещё пусто.
	 */
	private static List<BlockPos> starterPlots(ServerLevel level, Settlement settlement, List<BlockPos> plots) {
		int want = Math.max(0, Math.min(4, Rl2dopConfig.starterHouses));
		List<BlockPos> out = new ArrayList<>();
		for (BlockPos plot : plots) {
			if (out.size() >= want) {
				break;
			}
			if (settlement.hasHouseNear(plot)) {
				continue;
			}
			out.add(plot);
		}
		return out;
	}

	/**
	 * Жилые участки деревни: ряды вдоль главной улицы и переулка — но каждая точка плана
	 * «притирается» к рельефу: `Ground.pick()` сдвигает её на пару блоков и берёт ровное место
	 * рядом. Список детерминированный и кэшируется на игровой такт, поэтому `/house_add`,
	 * самострой и дороги всегда говорят об одних и тех же участках.
	 */
	public static List<BlockPos> housePlots(ServerLevel level, Settlement settlement) {
		BlockPos origin = settlement.hall();
		long key = origin.asLong();
		long tick = level == null ? 0L : level.getGameTime();
		PlotCache cached = PLOT_CACHE.get(key);
		if (cached != null && tick - cached.at < 400L) {
			return cached.plots;
		}
		List<BlockPos> out = new ArrayList<>();
		if (level == null) {
			for (int row = 0; row < PLOT_ROW_Z.length; row++) {
				for (int x : PLOT_ROW_X[row]) {
					out.add(origin.offset(x, 0, PLOT_ROW_Z[row]));
				}
			}
			return out;
		}
		Ground ground = new Ground(level, origin.getY());
		for (int row = 0; row < PLOT_ROW_Z.length; row++) {
			for (int x : PLOT_ROW_X[row]) {
				BlockPos ideal = origin.offset(x, 0, PLOT_ROW_Z[row]);
				if (!level.hasChunkAt(ideal)) {
					continue;   // участок за выгруженной зокой пока не считается местом для дома
				}
				BlockPos spot = ground.pick(ideal.getX(), ideal.getZ(), HOUSE_HALF + 2, HOUSE_HALF + 2);
				out.add(spot != null ? spot : ideal);
			}
		}
		// застраивается ближний круг, дальний — когда места рядом кончатся
		out.sort(java.util.Comparator.<BlockPos>comparingDouble(pos -> pos.distSqr(origin)));
		if (PLOT_CACHE.size() > 16) {
			PLOT_CACHE.clear();
		}
		PLOT_CACHE.put(key, new PlotCache(out, tick));
		return out;
	}

	/** Участки без привязки к рельефу — для расчётов, где мира под рукой нет. */
	public static List<BlockPos> housePlots(Settlement settlement) {
		return housePlots(null, settlement);
	}

	private static final class PlotCache {
		private final List<BlockPos> plots;
		private final long at;

		private PlotCache(List<BlockPos> plots, long at) {
			this.plots = plots;
			this.at = at;
		}
	}

	private static final Map<Long, PlotCache> PLOT_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

	/** Инвалидация кэша участков: /settle_build reset и ручная правка рельефа. */
	public static void forgetPlots(Settlement settlement) {
		PLOT_CACHE.remove(settlement.hall().asLong());
	}

	/** Первый участок, где дома ещё нет (null = деревня выросла до предела плана). */
	public static BlockPos freePlot(ServerLevel level, Settlement settlement) {
		for (BlockPos plot : housePlots(level, settlement)) {
			if (settlement.hasHouseNear(plot)) {
				continue;
			}
			if (!level.hasChunkAt(plot)) {
				continue;
			}
			return plot;
		}
		return null;
	}

	/** Сколько участков под дома есть в плане всего. */
	public static int plotCount() {
		int total = 0;
		for (int[] row : PLOT_ROW_X) {
			total += row.length;
		}
		return total;
	}

	/** Таверна: общий дом у двора ратуши — тут ждут работу приезжие, тут же перерывы и сходки. */
	private static void tavernPlan(Plan plan, Ground ground, ServerLevel level, Settlement settlement,
				BlockPos center) {
		int halfX = 5;
		int halfZ = 4;
		BlockPos spot = ground.pick(center.getX(), center.getZ(), halfX + 1, halfZ + 2);
		BlockPos at = spot != null ? spot : center;
		int y = ground.grade(plan, at.getX(), at.getZ(), halfX + 1, halfZ + 2, at.getY(),
				Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState());
		BlockPos base = new BlockPos(at.getX(), y, at.getZ());
		carve(plan, level, base, halfX, halfZ, 8);
		// пол
		for (int x = -halfX; x <= halfX; x++) {
			for (int z = -halfZ; z <= halfZ; z++) {
				plan.set(base.offset(x, 0, z), Math.abs(x) == halfX || Math.abs(z) == halfZ
						? Blocks.COBBLESTONE.defaultBlockState()
						: (x + z) % 2 == 0 ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.JUNGLE_PLANKS.defaultBlockState());
			}
		}
		// стены двух этажей, окна, вход с южной стороны
		for (int dy = 1; dy <= 6; dy++) {
			for (int x = -halfX; x <= halfX; x++) {
				for (int z : new int[]{-halfZ, halfZ}) {
					boolean front = z > 0;
					boolean doorway = front && dy <= 2 && Math.abs(x) <= 1;
					if (doorway) {
						continue;
					}
					BlockState wall = dy <= 3 ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState();
					if (front && dy == 2 && Math.abs(x) % 2 == 0) {
						plan.set(base.offset(x, dy, z), Blocks.GLASS.defaultBlockState());
						continue;
					}
					plan.set(base.offset(x, dy, z), wall);
				}
			}
			for (int z = -halfZ + 1; z <= halfZ - 1; z++) {
				for (int x : new int[]{-halfX, halfX}) {
					if (dy == 2 || dy == 5) {
						plan.set(base.offset(x, dy, z), Blocks.GLASS.defaultBlockState());
						continue;
					}
					plan.set(base.offset(x, dy, z), dy <= 3 ? Blocks.OAK_PLANKS.defaultBlockState()
							: Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState());
				}
			}
		}
		for (int x = -halfX; x <= halfX; x++) {
			plan.set(base.offset(x, 4, -halfZ), Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState());
			plan.set(base.offset(x, 4, halfZ), Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState());
		}
		door(plan, base.offset(0, 1, halfZ), Direction.SOUTH);
		plan.set(base.offset(0, 0, halfZ + 1), Blocks.STONE_BRICKS.defaultBlockState());
		plan.set(base.offset(0, 0, halfZ + 2), topSlab(Blocks.OAK_SLAB.defaultBlockState()));
		// крыша
		for (int x = -halfX - 1; x <= halfX + 1; x++) {
			for (int z = -halfZ - 1; z <= halfZ + 1; z++) {
				boolean edgeX = Math.abs(x) == halfX + 1;
				boolean edgeZ = Math.abs(z) == halfZ + 1;
				if (!edgeX && !edgeZ) {
					plan.set(base.offset(x, 7, z), Blocks.SPRUCE_PLANKS.defaultBlockState());
					continue;
				}
				Direction facing = edgeX ? (x > 0 ? Direction.WEST : Direction.EAST)
						: (z > 0 ? Direction.NORTH : Direction.SOUTH);
				plan.set(base.offset(x, 7, z), stairs(facing));
			}
		}
		// внутри: стойка, столы, очаг, свет — и два спальных места для приезжих
		for (int x = -3; x <= 1; x++) {
			plan.set(base.offset(x, 1, -halfZ + 1), Blocks.OAK_STAIRS.defaultBlockState()
					.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
			plan.set(base.offset(x, 2, -halfZ + 1), topSlab(Blocks.OAK_SLAB.defaultBlockState()));
		}
		// у бочки свойство facing шестистороннее — HORIZONTAL_FACING на него вешать нельзя (краш)
		for (int dx : new int[]{-3, 3}) {
			plan.set(base.offset(dx, 1, -halfZ + 2), Blocks.BARREL.defaultBlockState());
		}
		plan.set(base.offset(4, 1, -halfZ + 1), Blocks.SMOKER.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
		plan.set(base.offset(-4, 1, 2), Blocks.BREWING_STAND.defaultBlockState());
		plan.set(base.offset(3, 1, 2), Blocks.LOOM.defaultBlockState());
		for (int[] table : new int[][]{{-2, 1}, {2, -1}, {0, 3}}) {
			plan.set(base.offset(table[0], 1, table[1]), Blocks.OAK_FENCE.defaultBlockState());
			plan.set(base.offset(table[0], 2, table[1]), topSlab(Blocks.OAK_SLAB.defaultBlockState()));
			for (int[] seat : new int[][]{{1, 0}, {-1, 0}}) {
				plan.set(base.offset(table[0] + seat[0], 1, table[1] + seat[1]), Blocks.OAK_STAIRS.defaultBlockState()
						.setValue(BlockStateProperties.HORIZONTAL_FACING,
								seat[0] > 0 ? Direction.WEST : Direction.EAST));
			}
		}
		plan.set(base.offset(0, 1, -1), Blocks.CAMPFIRE.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
		for (int dx : new int[]{-3, 3}) {
			plan.set(base.offset(dx, 3, 0), Blocks.LANTERN.defaultBlockState());
			plan.set(base.offset(dx, 6, 0), Blocks.LANTERN.defaultBlockState());
		}
		// второй этаж: нары для тех, кто приехал и ещё не получил дом
		for (int x = -halfX + 1; x <= halfX - 1; x++) {
			plan.set(base.offset(x, 4, 0), Blocks.OAK_PLANKS.defaultBlockState());
		}
		for (int dz = -2; dz <= 2; dz += 4) {
			plan.set(base.offset(-4, 5, dz), Blocks.RED_BED.defaultBlockState()
					.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
					.setValue(BlockStateProperties.BED_PART, net.minecraft.world.level.block.state.properties.BedPart.FOOT));
			plan.set(base.offset(-3, 5, dz), Blocks.RED_BED.defaultBlockState()
					.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
					.setValue(BlockStateProperties.BED_PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD));
			plan.set(base.offset(4, 5, dz), Blocks.CHEST.defaultBlockState()
					.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
		}
		// лестница снаружи, фонарь над дверью, вывеска
		for (int dy = 1; dy <= 3; dy++) {
			plan.set(base.offset(2, dy, halfZ + 1), stairs(Direction.NORTH));
		}
		plan.set(base.offset(0, 3, halfZ + 2), Blocks.SEA_LANTERN.defaultBlockState());
		lampPost(plan, base.offset(-halfX - 2, 1, halfZ + 2), level);
		lampPost(plan, base.offset(halfX + 2, 1, halfZ + 2), level);
		settlement.setTavern(base.offset(0, 1, halfZ + 1));
	}

	/** Пруд: копается на блок ниже площади, берег — песок, по краю камыш. Рядом с таверной. */
	private static void pondPlan(Plan plan, Ground ground, ServerLevel level, BlockPos center) {
		int halfX = 4;
		int halfZ = 3;
		BlockPos spot = ground.pick(center.getX(), center.getZ(), halfX + 1, halfZ + 1);
		if (spot != null) {
			center = spot;
		}
		int y = center.getY();
		for (int x = -halfX; x <= halfX; x++) {
			for (int z = -halfZ; z <= halfZ; z++) {
				boolean shore = Math.abs(x) == halfX || Math.abs(z) == halfZ;
				BlockPos pos = new BlockPos(center.getX() + x, y, center.getZ() + z);
				if (shore) {
					plan.set(pos, Blocks.SAND.defaultBlockState());
					continue;
				}
				plan.set(pos, Blocks.WATER.defaultBlockState());
				plan.set(pos.below(), Blocks.CLAY.defaultBlockState());
			}
		}
		for (int[] reed : new int[][]{{-halfX + 2, -halfZ}, {halfX - 2, halfZ}, {-halfX, halfZ - 1}}) {
			plan.set(new BlockPos(center.getX() + reed[0], y + 1, center.getZ() + reed[1]),
					Blocks.SUGAR_CANE.defaultBlockState());
		}
		plan.set(new BlockPos(center.getX() + halfX - 1, y + 1, center.getZ() - halfZ + 1),
				Blocks.LILY_PAD.defaultBlockState());
		lampPost(plan, new BlockPos(center.getX() - halfX - 1, y + 1, center.getZ() + halfZ + 1), level);
	}

	/** Рабочие постройки в ряду за северной улицей: x = -30, -10, 10, 30. */
	public static BlockPos workBase(ServerLevel level, BlockPos origin, int index) {
		return new BlockPos(origin.getX() - 30 + index * WORK_STEP_X, origin.getY(), origin.getZ() + WORK_ROW_Z);
	}

	/** Дом на участке плана: сначала выравниваем площадку, потом кладём сруб 7x7. */
	public static void houseOnPlot(Plan plan, Ground ground, ServerLevel level, BlockPos plot) {
		int y = gradeHousePad(plan, ground, plot);
		housePlan(plan, level, new BlockPos(plot.getX(), y, plot.getZ()));
		// двор: забор по периметру участка с калиткой на дорогу
		for (int x = -HOUSE_HALF - 2; x <= HOUSE_HALF + 2; x++) {
			for (int z : new int[]{-HOUSE_HALF - 2, HOUSE_HALF + 3}) {
				if (z > 0 && Math.abs(x) <= 1) {
					continue;
				}
				plan.set(new BlockPos(plot.getX() + x, y + 1, plot.getZ() + z), Blocks.OAK_FENCE.defaultBlockState());
			}
		}
		for (int z = -HOUSE_HALF - 2; z <= HOUSE_HALF + 3; z++) {
			for (int x : new int[]{-HOUSE_HALF - 2, HOUSE_HALF + 2}) {
				plan.set(new BlockPos(plot.getX() + x, y + 1, plot.getZ() + z), Blocks.OAK_FENCE.defaultBlockState());
			}
		}
		plan.set(new BlockPos(plot.getX(), y + 1, plot.getZ() + HOUSE_HALF + 3), Blocks.OAK_FENCE_GATE.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
		lampPost(plan, new BlockPos(plot.getX() + HOUSE_HALF + 3, y + 1, plot.getZ() + HOUSE_HALF + 3), level);
	}

	/**
	 * Поставить таверну (и пруд) вручную: нужно поселениям, основанным до появления таверны,
	 * и команде /settle_tavern.
	 */
	public static BlockPos buildTavern(ServerLevel level, Settlement settlement) {
		BlockPos origin = settlement.hall();
		Plan plan = new Plan();
		Ground ground = new Ground(level, origin.getY());
		tavernPlan(plan, ground, level, settlement, origin.offset(TAVERN_X, 0, TAVERN_Z));
		if (Rl2dopConfig.villagePond) {
			pondPlan(plan, ground, level, origin.offset(POND_X, 0, POND_Z));
			ground.path(plan, origin.getX() + TAVERN_X + 4, origin.getZ() + TAVERN_Z,
					origin.getX() + POND_X - 6, origin.getZ() + TAVERN_Z, 0);
		}
		int placed = 0;
		for (Entry entry : plan.entries) {
			if (place(level, entry)) {
				placed++;
			}
		}
		EconomyState.get(level.getServer()).setDirty();
		return placed > 0 ? settlement.tavern() : null;
	}

	/** Построить дом мгновенно (команда /house_add). */
	public static BlockPos buildHouse(ServerLevel level, Settlement settlement, BlockPos standing) {
		BlockPos hall = settlement.hall();
		Ground ground = new Ground(level, hall.getY());
		// ищем ровное место рядом с запрошенной точкой: дом сам выбирает поверхность, а не
		// «уровень ратуши» — иначе на склоне он либо висел, либо резал холм котлованом
		BlockPos spot = ground.pick(standing.getX(), standing.getZ(), HOUSE_HALF + 2, HOUSE_HALF + 2);
		BlockPos at = spot != null ? spot : new BlockPos(standing.getX(),
				topSolidY(level, standing.getX(), standing.getZ(), standing.getY()), standing.getZ());
		Plan plan = new Plan();
		int graded = ground.grade(plan, at.getX(), at.getZ(), HOUSE_HALF + 2, HOUSE_HALF + 2, at.getY(),
				Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState());
		BlockPos base = new BlockPos(at.getX(), graded, at.getZ());
		housePlan(plan, level, base);
		// и сразу связь с деревней: дорожка от калитки до ближайшей улицы
		int streetZ = nearestStreetZ(base.getZ() - hall.getZ());
		ground.path(plan, base.getX(), base.getZ() + HOUSE_HALF + 3, base.getX(), hall.getZ() + streetZ, 0);
		int built = 0;
		for (Entry entry : plan.entries) {
			if (place(level, entry)) {
				built++;
			}
		}
		settlement.addHouse(base);
		forgetPlots(settlement);   // участок занят — список мест для следующих домов должен обновиться
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
			registerStarterHouses(level, settlement);
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
	/** План застройки, применённый сразу; ошибки не выпускаем наружу — см. {@link #applySafely}. */
	public static int buildInstantSafe(ServerLevel level, Settlement settlement) {
		return applySafely(level, settlement, 0);
	}

	/**
	 * Применить план целиком (budget = 0) или частью, не роняя ни команду, ни тик сервера:
	 * кривой рельеф или невыгруженный чанок должны остаться в логе, а не в краше мира.
	 */
	public static int buildSafe(ServerLevel level, Settlement settlement, int budget) {
		return applySafely(level, settlement, budget);
	}

	private static int applySafely(ServerLevel level, Settlement settlement, int budget) {
		try {
			return budget <= 0 ? buildInstant(level, settlement) : build(level, settlement, budget);
		} catch (RuntimeException e) {
			com.baraaoi.rl2dop.Rl2dop.LOGGER.error("[rl2dop] поселение {}: план застройки не применился",
					settlement.id, e);
			settlement.buildProgress = 100;
			EconomyState.get(level.getServer()).log("поселение " + settlement.id
					+ ": застройка остановилась на ошибке (" + e.getClass().getSimpleName()
					+ ") — остальное можно достроить /settle_build " + settlement.id + " instant");
			EconomyState.get(level.getServer()).setDirty();
			return settlement.buildProgress;
		}
	}

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
	private static void registerStarterHouses(ServerLevel level, Settlement settlement) {
		for (BlockPos plot : starterPlots(level, settlement, housePlots(level, settlement))) {
			// уровень берём у самого участка: площадка дома может быть на блок-другой ниже/выше
			// площади, и именно эта высота потом считается «полом» при расселении
			settlement.addHouse(new BlockPos(plot.getX(), plot.getY(), plot.getZ()));
		}
	}

	/** Пятно таверны/пруда внутри площади: мощение туда не кладём. */
	private static boolean isTavernPlot(BlockPos origin, int x, int z) {
		return Rl2dopConfig.tavernEnabled && Math.abs(x - TAVERN_X) <= 7 && Math.abs(z - TAVERN_Z) <= 6;
	}

	private static boolean isPondPlot(BlockPos origin, int x, int z) {
		return Rl2dopConfig.tavernEnabled && Rl2dopConfig.villagePond && Math.abs(x - POND_X) <= 5
				&& Math.abs(z - POND_Z) <= 4;
	}

	/** Колодец: сруб 2x2 с водой, крыша на столбах — центр жизни деревни. */
	private static void well(Plan plan, BlockPos at) {
		for (int x = 0; x <= 1; x++) {
			for (int z = 0; z <= 1; z++) {
				plan.set(at.offset(x, 0, z), Blocks.COBBLESTONE.defaultBlockState());
			}
		}
		plan.set(at.offset(0, 1, 0), Blocks.WATER.defaultBlockState());
		for (int[] post : new int[][]{{0, 0}, {1, 1}}) {
			plan.set(at.offset(post[0], 2, post[1]), Blocks.OAK_FENCE.defaultBlockState());
			plan.set(at.offset(post[0], 3, post[1]), Blocks.OAK_FENCE.defaultBlockState());
		}
		for (int x = -1; x <= 2; x++) {
			plan.set(at.offset(x, 4, 0), Blocks.SPRUCE_SLAB.defaultBlockState());
			plan.set(at.offset(x, 4, 1), Blocks.SPRUCE_SLAB.defaultBlockState());
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
	/**
	 * Модель рельефа посёлка: натуральный грунт + уровни площадок, которые посёлок задал сам.
	 * Нужна для того, чтобы каждая постройка выбирала РОВНОЕ место сама (как кусок ванильной
	 * деревни), а дороги связывали эти места простыми ступенями: и постройка, и дорога считают
	 * высоту по одной модели, поэтому они не спорят за один и тот же блок.
	 */
	private static final class Ground {
		private final ServerLevel level;
		private final int baseY;
		/** x;z (как long) → уровень, который посёлок задал этой колонке. */
		private final Map<Long, Integer> planned = new HashMap<>();
		/** x;z → натуральный грунт: читаем один раз на колонку, план строится в одном тике. */
		private final Map<Long, Integer> natural = new HashMap<>();

		Ground(ServerLevel level, int baseY) {
			this.level = level;
			this.baseY = baseY;
		}

		private static long key(int x, int z) {
			return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
		}

		int natural(int x, int z) {
			long k = key(x, z);
			Integer cached = natural.get(k);
			if (cached != null) {
				return cached;
			}
			int y = topSolidY(level, x, z, baseY);
			natural.put(k, y);
			return y;
		}

		/** Текущий уровень колонки: либо тот, что задал посёлок, либо природный. */
		int level(int x, int z) {
			Integer set = planned.get(key(x, z));
			return set != null ? set : natural(x, z);
		}

		/**
		 * Уровень, при котором пятно halfX×halfZ требует меньше всего земли: мода высот грунта
		 * (окна 2 блока), не выше +8 и не ниже -8 от уровня поселения.
		 */
		int padLevel(int cx, int cz, int halfX, int halfZ) {
			// шаг выборки 2 блока → (halfX+1)×(halfZ+1) колонок; массивы размеряем по пятну,
			// иначе площадка мастерской (9×8) уже ломала сборку выборки за границы массива
			int nx = halfX + 1;   // шаг выборки 2 → ровно halfX+1 колонок вдоль оси X
			int nz = halfZ + 1;
			int[] samples = new int[nx * nz];
			int n = 0;
			int min = Integer.MAX_VALUE;
			int max = Integer.MIN_VALUE;
			for (int z = cz - halfZ; z <= cz + halfZ; z += 2) {
				for (int x = cx - halfX; x <= cx + halfX; x += 2) {
					int y = level(x, z);
					if (n < samples.length) {
						samples[n] = y;
					}
					n++;
					min = Math.min(min, y);
					max = Math.max(max, y);
				}
			}
			if (n == 0) {
				return baseY;
			}
			if (max - min >= 64) {
				return clampBase(min);
			}
			int[] counts = new int[max - min + 1];
			for (int i = 0; i < Math.min(n, samples.length); i++) {
				counts[samples[i] - min]++;
			}
			int best = 0;
			int bestCount = -1;
			for (int i = 0; i < counts.length; i++) {
				if (counts[i] > bestCount) {
					bestCount = counts[i];
					best = i;
				}
			}
			return clampBase(min + best);
		}

		private int clampBase(int y) {
			return Math.max(baseY - 8, Math.min(baseY + 8, y));
		}

		/**
		 * Перепад «пола» над пятном: высоту каждой колонки ищем ОТ уровня y вниз, поэтому крыши,
		 * заборы и фонари над площадкой не считаются кривизной (иначе аудит ругался бы на ратушу).
		 */
		int floorSpread(int cx, int cz, int y, int half) {
			int min = Integer.MAX_VALUE;
			int max = Integer.MIN_VALUE;
			for (int z = cz - half; z <= cz + half; z++) {
				for (int x = cx - half; x <= cx + half; x++) {
					int v = groundTopY(level, x, z, y);
					min = Math.min(min, v);
					max = Math.max(max, v);
			}
			}
			return max == Integer.MIN_VALUE ? 0 : max - min;
		}

		/** Перепад высот над пятном (0 = идеально ровно). */
		int spread(int cx, int cz, int halfX, int halfZ) {
			int min = Integer.MAX_VALUE;
			int max = Integer.MIN_VALUE;
			for (int z = cz - halfZ; z <= cz + halfZ; z += 2) {
				for (int x = cx - halfX; x <= cx + halfX; x += 2) {
					int y = level(x, z);
					min = Math.min(min, y);
					max = Math.max(max, y);
				}
			}
			return max == Integer.MIN_VALUE ? 0 : max - min;
		}

		/** Объём земляных работ при выравнивании пятна в `y` (в блоках, по грубой сетке). */
		private int work(int cx, int cz, int halfX, int halfZ, int y) {
			int sum = 0;
			for (int z = cz - halfZ; z <= cz + halfZ; z += 2) {
				for (int x = cx - halfX; x <= cx + halfX; x += 2) {
					sum += Math.abs(level(x, z) - y);
				}
			}
			return sum;
		}

		/** Есть ли в пятне вода/лава — такое место построению не подходит. */
		private boolean wet(int cx, int cz, int halfX, int halfZ) {
			for (int z = cz - halfZ; z <= cz + halfZ; z += 2) {
				for (int x = cx - halfX; x <= cx + halfX; x += 2) {
					BlockPos pos = new BlockPos(x, level(x, z) + 1, z);
					if (!level.hasChunkAt(pos)) {
						continue;
					}
					// любая жидкость в пятне (вода, лава) — место под постройку не подходит;
					// теги в Mojang-маппинге лежат в другом пакете, поэтому смотрим на сам FluidState
					if (!level.getFluidState(pos).isEmpty()) {
						return true;
					}
				}
			}
			return false;
		}

		/**
		 * Ищем место под пятно halfX×halfZ рядом с (cx,cz): сдвигаемся на пару блоков туда-сюда
		 * и выбираем вариант с минимальным перепадом и минимальным объёмом работ. Так дом (или
		 * таверна, или мастерская) встаёт на ровное, а не на «уровень ратуши любой ценой».
		 */
		BlockPos pick(int cx, int cz, int halfX, int halfZ) {
			return pick(cx, cz, halfX, halfZ, 2, 2);
		}

		/**
		 * @param radius насколько блоков можно сдвинуться (в клетках по `step`)
		 * @param step   шаг поиска: 2 — точечно под дом, 3 — обзор под весь посёлок
		 */
		BlockPos pick(int cx, int cz, int halfX, int halfZ, int radius, int step) {
			BlockPos ideal = new BlockPos(cx, baseY, cz);
			if (!level.hasChunkAt(ideal)) {
				return null;
			}
			BlockPos best = null;
			int bestScore = Integer.MAX_VALUE;
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dx = -radius; dx <= radius; dx++) {
					int x = cx + dx * step;
					int z = cz + dz * step;
					if (!level.hasChunkAt(new BlockPos(x, baseY, z))) {
						continue;
					}
					int y = padLevel(x, z, halfX, halfZ);
					int score = spread(x, z, halfX, halfZ) * 10 + work(x, z, halfX, halfZ, y) * 3
							+ (dx * dx + dz * dz) + Math.abs(y - baseY) * 4;
					if (wet(x, z, halfX, halfZ)) {
						score += 120;
					}
					if (score < bestScore) {
						bestScore = score;
						best = new BlockPos(x, y, z);
					}
				}
			}
			return best;
		}

		/** Ровняем пятно под `y`: срезаем холм, досыпаем яму, сверху `top`; помечаем колонки занятыми. */
		int grade(Plan plan, int cx, int cz, int halfX, int halfZ, int y, BlockState top, BlockState fill) {
			for (int x = cx - halfX; x <= cx + halfX; x++) {
				for (int z = cz - halfZ; z <= cz + halfZ; z++) {
					gradeCell(plan, x, z, y, top, fill, true);
				}
			}
			// откос в 1 блок по краю: чтобы площадка не была обрывом, а скатывалась к грунту
			for (int x = cx - halfX - 1; x <= cx + halfX + 1; x++) {
				for (int z = cz - halfZ - 1; z <= cz + halfZ + 1; z++) {
					boolean inside = x >= cx - halfX && x <= cx + halfX && z >= cz - halfZ && z <= cz + halfZ;
					if (inside) {
						continue;
					}
					int edge = Math.max(Math.abs(x - cx) - halfX, Math.abs(z - cz) - halfZ);
					if (edge != 1) {
						continue;
					}
					int want = Math.max(y - 1, Math.min(y + 1, natural(x, z)));
					gradeCell(plan, x, z, want, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
							false);
				}
			}
			return y;
		}

		/**
		 * Одна колонка: всё природное выше `y` срезать, недосып досыпать, верх — `top`.
		 * `force=false` не трогает колонку, которую уже заняла площадка постройки.
		 */
		void gradeCell(Plan plan, int x, int z, int y, BlockState top, BlockState fill, boolean force) {
			long k = key(x, z);
			if (!force && planned.containsKey(k)) {
				return;
			}
			int ground = natural(x, z);
			for (int yy = y + 1; yy <= ground; yy++) {
				BlockPos pos = new BlockPos(x, yy, z);
				if (removable(level, pos) && !level.getBlockState(pos).isAir()) {
					plan.air(pos);
				}
			}
			for (int yy = ground + 1; yy < y; yy++) {
				plan.set(new BlockPos(x, yy, z), fill);
			}
			plan.set(new BlockPos(x, y, z), top);
			planned.put(k, y);
		}

		/**
		 * Дорога между точками: лента шириной 1 + 2*halfWidth, которая идёт по грунту и меняет
		 * высоту не больше чем на блок на шаге. Никаких рамп и террас на 20 блоков — простая
		 * лесенка, как в ванильной деревне. Колонки чужих площадок не перетираем.
		 */
		void path(Plan plan, int x0, int z0, int x1, int z1, int halfWidth) {
			path(plan, x0, z0, x1, z1, halfWidth, null);
		}

		/** `top == null` — мостовая пёстрая (`roadTop`), как в ванильной деревне. */
		void path(Plan plan, int x0, int z0, int x1, int z1, int halfWidth, BlockState top) {
			int steps = Math.max(Math.abs(x1 - x0), Math.abs(z1 - z0));
			if (steps < 0) {
				return;
			}
			boolean alongX = Math.abs(x1 - x0) >= Math.abs(z1 - z0);
			int prevY = level(x0, z0);
			for (int i = 0; i <= steps; i++) {
				double t = steps == 0 ? 1.0D : (double) i / (double) steps;
				int cx = x0 + (int) Math.round((x1 - x0) * t);
				int cz = z0 + (int) Math.round((z1 - z0) * t);
				int want = level(cx, cz);
				int y = Math.max(prevY - 1, Math.min(prevY + 1, want));
				for (int w = -halfWidth; w <= halfWidth; w++) {
					int px = alongX ? cx : cx + w;
					int pz = alongX ? cz + w : cz;
					gradeCell(plan, px, pz, y, top != null ? top : roadTop(px, pz),
							Blocks.COBBLESTONE.defaultBlockState(), false);
				}
				prevY = y;
			}
		}

		/** Ступенька между двумя уровнями, если соседняя площадка выше/ниже дороги на 2+ блока. */
		void step(Plan plan, int x, int z, int y) {
			gradeCell(plan, x, z, y, roadTop(x, z), Blocks.COBBLESTONE.defaultBlockState(), false);
		}
	}

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
