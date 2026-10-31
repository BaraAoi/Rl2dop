package com.baraaoi.rl2dop.entity;

import com.baraaoi.rl2dop.Rl2dopConfig;
import com.baraaoi.rl2dop.economy.EconomyState;
import com.baraaoi.rl2dop.economy.MarketMath;
import com.baraaoi.rl2dop.economy.Settlement;
import com.baraaoi.rl2dop.economy.SettlementBuilds;
import com.baraaoi.rl2dop.economy.SettlementService;
import com.baraaoi.rl2dop.economy.VillageLife;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Arrow;

import java.util.ArrayList;
import java.util.List;

/**
 * Мозг жителя. Вызывается не каждый тик, а раз в {@code brainIntervalTicks},
 * поэтому 100 NPC не съедают TPS.
 */
public final class CitizenBrain {
	private CitizenBrain() {
	}

	public static void tick(CitizenEntity citizen) {
		if (!(citizen.level() instanceof ServerLevel level)) {
			return;
		}
		EconomyState state = EconomyState.get(level.getServer());
		if (citizen.getCitizenName().isEmpty()) {
			citizen.setCitizenName(EconomyState.randomName(citizen.getRandom()));
		}
		// Распорядок дня вместо «всегда работа»: жители ходят на своё место, в полдень
		// собираются на перерыв у таверны, вечером — у колокола, ночью спят дома.
		// Занятие считаем ДО ранних выходов: оно показывает, чем житель должен быть занят,
		// и иначе подпись над головой отстаёт от расписания на целый игровой день.
		CitizenActivity activity = schedule(level, citizen);
		citizen.setActivity(activity);
		Settlement settlement = state.settlement(citizen.getSettlementId());
		if (settlement == null) {
			citizen.setCitizenState(CitizenState.IDLE);
			return;
		}
		if (handleGuard(citizen, level, settlement)) {
			return;
		}
		if (citizen.getHunger() < 35 && handleHunger(citizen, level, settlement)) {
			return;
		}
		switch (activity) {
			case POST -> handlePost(citizen);
			case SLEEP -> handleSleep(citizen);
			case REST -> handleRest(citizen);
			case BREAK -> handleBreak(citizen, level, settlement);
			case MEET -> handleMeet(citizen, level, settlement);
			case WAIT_JOB -> handleWaitJob(citizen, level, settlement);
			default -> handleWork(citizen, level, settlement);
		}
		paySalary(citizen, settlement);
		maybeAutoSwitch(citizen, level, settlement);
	}

	// ---------- распорядок дня ----------

	/**
	 * Чем занят житель прямо сейчас. Расписание взято с ванильного {@code Schedule} жителей
	 * (work → meet → rest) и дополнено двумя вещами: полуденным перерывом у таверны и
	 * ожиданием работы для тех, кто приехал без профессии.
	 */
	public static CitizenActivity schedule(ServerLevel level, CitizenEntity citizen) {
		Profession profession = citizen.getProfession();
		if (profession.isStatic()) {
			return CitizenActivity.POST;
		}
		long day = level.getDayTime() % 24000L;
		if (isNight(level) || day >= 23000L) {
			return CitizenActivity.SLEEP;
		}
		if (profession.isJobless()) {
			return CitizenActivity.WAIT_JOB;
		}
		if (!Rl2dopConfig.citizenSchedule) {
			return CitizenActivity.WORK;
		}
		long workStart = Math.max(0, Rl2dopConfig.dayWorkStart);
		long breakStart = Math.max(workStart + 500L, Rl2dopConfig.dayBreakStart);
		long meetStart = Math.max(breakStart + 2000L, Rl2dopConfig.dayMeetStart);
		if (day < workStart) {
			return CitizenActivity.WAKE;
		}
		if (day < breakStart) {
			return CitizenActivity.WORK;
		}
		if (day < breakStart + 2000L) {
			return CitizenActivity.BREAK;
		}
		if (day < meetStart) {
			return CitizenActivity.WORK;
		}
		if (day < meetStart + 1500L) {
			return CitizenActivity.MEET;
		}
		return CitizenActivity.REST;
	}

	/** Личная точка у места встреч: чтобы жители собирались вместе, но не вставали друг в друга. */
	private static BlockPos gatherSpot(CitizenEntity citizen, ServerLevel level, Settlement settlement) {
		BlockPos spot = SettlementService.gatherPoint(level, settlement);
		int hash = Math.abs(citizen.getUUID().hashCode());
		BlockPos at = spot.offset(hash % 5 - 2, 0, (hash / 7) % 5 - 2);
		return SettlementBuilds.safeStand(level, at);
	}

	/** Сколько жителей рядом (для «болтают на перерыве»). */
	private static int nearby(CitizenEntity citizen, ServerLevel level, double radius) {
		return level.getEntitiesOfClass(CitizenEntity.class, citizen.getBoundingBox().inflate(radius)).size() - 1;
	}

	/** Полуденный перерыв: идут к таверне, едят то, что есть на складе, болтают. */
	private static void handleBreak(CitizenEntity citizen, ServerLevel level, Settlement settlement) {
		BlockPos spot = gatherSpot(citizen, level, settlement);
		// «пришёл» — это 5 блоков, а не 2: на перерыв собирается вся деревня, и с узким окном
		// жители только толкались у таверны, так и не переходя к еде и разговорам
		if (citizen.blockPosition().distSqr(spot) > 25.0D) {
			citizen.setCitizenState(CitizenState.WALKING);
			if (citizen.getNavigation().isDone()) {
				walkTo(citizen, spot);
			}
			return;
		}
		citizen.getNavigation().stop();
		citizen.setCitizenState(CitizenState.IDLE);
		citizen.lookAtBlock(spot);
		if (citizen.getRandom().nextInt(3) != 0) {
			return;
		}
		String food = "minecraft:bread";
		Rl2dopConfig.Good good = Rl2dopConfig.good(food);
		int price = good == null ? 0 : MarketMath.buyPrice(settlement, good, 0, 1.0D);
		if (good != null && settlement.stock(food) > 0 && citizen.getWallet() >= price) {
			citizen.setWallet(citizen.getWallet() - price);
			settlement.treasury += price;
			settlement.takeStock(food, 1);
			citizen.addCarried(food, 1);
			citizen.setHunger(citizen.getHunger() + 15);
			citizen.setMood(citizen.getMood() + 2);
			EconomyState.get(level.getServer()).setDirty();
		} else {
			// пообедать нечем — это сразу видно на табличке нужд
			settlement.addDemand(food, 2);
			citizen.setMood(citizen.getMood() - 1);
		}
		int with = Math.max(0, nearby(citizen, level, 6.0D));
		if (with > 0) {
			citizen.setMood(citizen.getMood() + 1);
			// «собрались вместе» — репортаж не по рандому, а раз в ~20 секунд реального времени:
			// на случайной проверке строку можно было не дождаться ни в игре, ни в smoke-тесте
			if (with >= 2 && level.getGameTime() % 400L < 10L) {
				EconomyState.get(level.getServer()).log("поселение " + settlement.id + ": перерыв у таверны, собралось "
						+ (with + 1) + " жител(ей)");
			}
		}
	}

	/** Вечерняя сходка (ванильный «meet»): у колокола, новости, настроение и репутация игрокам рядом. */
	private static void handleMeet(CitizenEntity citizen, ServerLevel level, Settlement settlement) {
		BlockPos spot = gatherSpot(citizen, level, settlement);
		if (citizen.blockPosition().distSqr(spot) > 25.0D) {   // та же ширина «я на месте», что и на перерыве
			citizen.setCitizenState(CitizenState.WALKING);
			if (citizen.getNavigation().isDone()) {
				walkTo(citizen, spot);
			}
			return;
		}
		citizen.getNavigation().stop();
		citizen.setCitizenState(CitizenState.IDLE);
		citizen.lookAtBlock(settlement.hall());
		citizen.setMood(citizen.getMood() + 1);
		for (net.minecraft.server.level.ServerPlayer player
				: level.getEntitiesOfClass(net.minecraft.server.level.ServerPlayer.class,
						citizen.getBoundingBox().inflate(10.0D))) {
			if (citizen.getRandom().nextInt(20) == 0) {
				// мирный игрок рядом на сходке — деревня это запоминает
				settlement.addReputation(player.getUUID(), 1);
			}
		}
	}

	/** Вечер: идёт домой, ложится уже ночью. */
	private static void handleRest(CitizenEntity citizen) {
		BlockPos home = citizen.getHomePos();
		if (home == null) {
			citizen.getNavigation().stop();
			citizen.setCitizenState(CitizenState.IDLE);
			return;
		}
		BlockPos bed = bedSpot(citizen, home);
		if (citizen.blockPosition().distSqr(bed) > 4.0D) {
			citizen.setCitizenState(CitizenState.WALKING);
			if (citizen.getNavigation().isDone()) {
				walkTo(citizen, bed);
			}
		} else {
			citizen.getNavigation().stop();
			citizen.setCitizenState(CitizenState.IDLE);
		}
	}

	/** Безработный: живёт при таверне, слоняется и ждёт, когда его позовут на работу. */
	private static void handleWaitJob(CitizenEntity citizen, ServerLevel level, Settlement settlement) {
		BlockPos spot = gatherSpot(citizen, level, settlement);
		citizen.setCitizenState(CitizenState.WAITING);
		if (citizen.blockPosition().distSqr(spot) > 9.0D) {
			if (citizen.getNavigation().isDone()) {
				walkTo(citizen, spot);
			}
			return;
		}
		if (citizen.getRandom().nextInt(8) == 0) {
			walkTo(citizen, SettlementBuilds.safeStand(level, SettlementService.jitter(level, spot, 2)));
		} else {
			citizen.getNavigation().stop();
		}
		if (citizen.getRandom().nextInt(30) == 0) {
			citizen.setMood(citizen.getMood() - 1);
		}
	}

	// ---------- стража ----------

	private static boolean handleGuard(CitizenEntity citizen, ServerLevel level, Settlement settlement) {
		if (citizen.getProfession() != Profession.GUARD) {
			return false;
		}
		Player target = null;
		double best = 16.0D * 16.0D;
		for (Player player : level.getEntitiesOfClass(Player.class, citizen.getBoundingBox().inflate(16.0D))) {
			if (!(player instanceof ServerPlayer serverPlayer) || serverPlayer.isCreative() || serverPlayer.isSpectator()) {
				continue;
			}
			if (settlement.reputation(serverPlayer.getUUID()) > Rl2dopConfig.evilReputation) {
				continue;
			}
			double distance = citizen.distanceToSqr(serverPlayer);
			if (distance < best) {
				best = distance;
				target = serverPlayer;
			}
		}
		if (target != null) {
			citizen.setTarget(target);
			citizen.setCitizenState(CitizenState.ATTACK);
			if (citizen.isArcherGuard() && citizen.distanceToSqr(target) > 9.0D && citizen.tickCount % 40 == 0) {
				shootArrow(citizen, level, target);
			}
			return true;
		}
		LivingEntity current = citizen.getTarget();
		if (current != null && (current.isDeadOrDying() || citizen.distanceToSqr(current) > 1024.0D)) {
			citizen.setTarget(null);
		}
		return false;
	}

	private static void shootArrow(CitizenEntity citizen, ServerLevel level, LivingEntity target) {
		Arrow arrow = new Arrow(EntityType.ARROW, level);
		arrow.setOwner(citizen);
		arrow.setPos(citizen.getX(), citizen.getEyeY() - 0.2D, citizen.getZ());
		double dx = target.getX() - citizen.getX();
		double dy = target.getY(0.5D) - arrow.getY();
		double dz = target.getZ() - citizen.getZ();
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		arrow.shoot(dx, dy + horizontal * 0.2D, dz, 1.6F, 6.0F);
		level.addFreshEntity(arrow);
	}

	/**
	 * «Пост»: глава стоит у табличок и день, и ночь — только если голоден, отлучается в лавку
	 * (handleHunger отрабатывает до этого) и возвращается.
	 */
	private static void handlePost(CitizenEntity citizen) {
		BlockPos post = citizen.getWorkPos();
		if (post == null) {
			citizen.getNavigation().stop();
			citizen.setCitizenState(CitizenState.IDLE);
			return;
		}
		if (citizen.blockPosition().distSqr(post) > 4.0D) {
			citizen.setCitizenState(CitizenState.WALKING);
			if (citizen.getNavigation().isDone()) {
				walkTo(citizen, post);
			}
			return;
		}
		citizen.getNavigation().stop();
		citizen.setCitizenState(CitizenState.IDLE);
		citizen.lookAtBlock(post);
	}

	// ---------- голод ----------

	private static boolean handleHunger(CitizenEntity citizen, ServerLevel level, Settlement settlement) {
		String food = "minecraft:bread";
		Rl2dopConfig.Good good = Rl2dopConfig.good(food);
		if (good == null) {
			citizen.setHunger(50);
			return false;
		}
		if (citizen.carried(food) > 0) {
			citizen.addCarried(food, -1);
			citizen.setHunger(citizen.getHunger() + 45);
			citizen.setMood(citizen.getMood() + 1);
			citizen.setCitizenState(CitizenState.EATING);
			return true;
		}
		int price = MarketMath.buyPrice(settlement, good, 0, 1.0D);
		if (Rl2dopConfig.infiniteNpcMoney) {
			// временно: деньги у жителей есть всегда — еда не должна упираться в кошелёк
			citizen.setWallet(Math.max(citizen.getWallet(), price * 4));
		}
		if (citizen.getWallet() < price && settlement.treasury >= (long) price * 4L) {
			// соцпомощь из казны: житель не должен умирать с голоду
			settlement.treasury -= price;
			citizen.addWallet(price);
			citizen.setMood(citizen.getMood() - 1);
		}
		if (citizen.getWallet() < price || settlement.stock(food) <= 0) {
			citizen.setMood(citizen.getMood() - 2);
			citizen.setCitizenState(citizen.getHunger() <= 0 ? CitizenState.PANIC : CitizenState.IDLE);
			return false;
		}
		BlockPos shop = citizen.getShopPos();
		if (shop == null) {
			return false;
		}
		if (citizen.blockPosition().distSqr(shop) > 9.0D) {
			citizen.setCitizenState(CitizenState.SHOPPING);
			if (citizen.getNavigation().isDone()) {
				walkTo(citizen, shop);
			}
			return true;
		}
		citizen.getNavigation().stop();
		citizen.setWallet(citizen.getWallet() - price);
		settlement.treasury += price;
		settlement.takeStock(food, 1);
		settlement.addDemand(food, 2);
		citizen.setHunger(citizen.getHunger() + 45);
		citizen.setCitizenState(CitizenState.EATING);
		EconomyState.get(level.getServer()).setDirty();
		return true;
	}

	// ---------- сон ----------

	/**
	 * Дом для навигации: в `homePos` хранится уровень двери дома (та же точка, по которой поселение
	 * считает жильё), а идти нужно на блок, где можно стоять, — иначе житель упирается в пол.
	 */
	private static BlockPos bedSpot(CitizenEntity citizen, BlockPos home) {
		return citizen.level() instanceof ServerLevel level ? SettlementBuilds.safeStand(level, home) : home;
	}

	private static void handleSleep(CitizenEntity citizen) {
		citizen.setCitizenState(CitizenState.SLEEPING);
		BlockPos home = citizen.getHomePos();
		if (home == null) {
			citizen.getNavigation().stop();
			return;
		}
		BlockPos bed = bedSpot(citizen, home);
		if (citizen.blockPosition().distSqr(bed) > 4.0D) {
			if (citizen.getNavigation().isDone()) {
				walkTo(citizen, bed);
			}
		} else {
			citizen.getNavigation().stop();
			citizen.setDeltaMovement(0.0D, citizen.getDeltaMovement().y, 0.0D);
		}
	}

	// ---------- работа ----------

	private static void handleWork(CitizenEntity citizen, ServerLevel level, Settlement settlement) {
		Profession profession = citizen.getProfession();
		if (profession == Profession.TRADER || profession == Profession.GUARD) {
			BlockPos spot = profession == Profession.TRADER ? citizen.getShopPos() : citizen.getWorkPos();
			if (spot != null && citizen.blockPosition().distSqr(spot) > 16.0D) {
				citizen.setCitizenState(CitizenState.WALKING);
				if (citizen.getNavigation().isDone()) {
					walkTo(citizen, spot);
				}
			} else {
				citizen.getNavigation().stop();
				citizen.setCitizenState(CitizenState.IDLE);
			}
			return;
		}
		BlockPos work = citizen.getWorkPos();
		if (work == null) {
			citizen.setCitizenState(CitizenState.IDLE);
			return;
		}
		double leash = Math.max(2, Rl2dopConfig.workLeash);
		if (citizen.blockPosition().distSqr(work) > leash * leash) {
			citizen.setCitizenState(CitizenState.WALKING);
			if (citizen.getNavigation().isDone()) {
				walkTo(citizen, work);
			}
			return;
		}
		citizen.getNavigation().stop();
		citizen.setCitizenState(CitizenState.WORKING);

		// посёлку нужен дом — строитель идёт на участок и строит, станок ждёт
		if (profession == Profession.BUILDER && settlement.hasHouseGoal()) {
			citizen.setCitizenState(CitizenState.BUILDING);
			VillageLife.buildGoal(level, settlement, citizen);
			return;
		}

		int threshold = Math.max(1, profession.workTicks() / Math.max(1, Rl2dopConfig.brainIntervalTicks));
		citizen.setWorkTicks(citizen.getWorkTicks() + 1);
		if (citizen.getWorkTicks() < threshold) {
			return;
		}
		citizen.setWorkTicks(0);

		if (profession.consumes()) {
			String input = profession.consumeItem();
			int need = profession.consumeAmount();
			if (settlement.warehouse(input) >= need) {
				settlement.takeWarehouse(input, need);
				settlement.addDemand(input, 1);
			} else if (settlement.stock(input) >= need) {
				settlement.takeStock(input, need);
				settlement.addDemand(input, 2);
			} else {
				// сырья нет — работа встаёт, и это сразу видно на табличке нужд
				settlement.addDemand(input, 3);
				return;
			}
		}
		if (profession.produces()) {
			settlement.addStock(profession.produceItem(), profession.produceAmount());
			settlement.addDemand(profession.produceItem(), -1);
		}
		// дом шахтера работает сам: шахтёр стоит у своей шахты и производит камень и руду
		if (profession == Profession.MINER) {
			settlement.addStock("minecraft:cobblestone", 2);
			settlement.addDemand("minecraft:cobblestone", -1);
			int luck = citizen.getRandom().nextInt(6);
			if (luck == 0) {
				settlement.addStock("minecraft:iron_ingot", 1);
				settlement.addDemand("minecraft:iron_ingot", -1);
			} else if (luck == 1) {
				settlement.addStock("minecraft:coal", 1);
				settlement.addDemand("minecraft:coal", -1);
			} else if (luck == 2 && Rl2dopConfig.workshopYield > 0) {
				settlement.addStock("minecraft:coal", 1);
				settlement.addDemand("minecraft:coal", -1);
			}
		}
		if (profession == Profession.FARMER && citizen.getRandom().nextInt(3) == 0) {
			// на ферме всегда что-то да вырастает: морковь/картофель разбавляют склад
			String extra = citizen.getRandom().nextBoolean() ? "minecraft:carrot" : "minecraft:wheat";
			settlement.addStock(extra, 1);
			settlement.addDemand(extra, -1);
		}
		if (profession == Profession.BUILDER) {
			// ратуша не нуждается в стройке, поэтому строитель растит жилые дома
			VillageLife.buildGoal(level, settlement, citizen);
		}
		if (profession == Profession.HEALER) {
			healNearby(citizen, level);
		}
		// не стоит истуканом на месте: перебирается к соседней грядке/станку, но не уходит далеко
		if (citizen.getRandom().nextInt(5) == 0) {
			walkTo(citizen, SettlementBuilds.safeStand(level, SettlementService.jitter(level, work, 2)));
		}
		EconomyState.get(level.getServer()).setDirty();
	}

	private static void healNearby(CitizenEntity healer, ServerLevel level) {
		for (Player player : level.getEntitiesOfClass(Player.class, healer.getBoundingBox().inflate(8.0D))) {
			if (player.getHealth() < player.getMaxHealth()) {
				player.heal(1.0F);
			}
		}
		for (CitizenEntity other : level.getEntitiesOfClass(CitizenEntity.class, healer.getBoundingBox().inflate(8.0D))) {
			if (other != healer && other.getHealth() < other.getMaxHealth()) {
				other.heal(1.0F);
			}
		}
		if (healer.getRandom().nextInt(6) == 0) {
			healer.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND,
					new net.minecraft.world.item.ItemStack(com.baraaoi.rl2dop.registry.ModItems.BANDAGE, 1));
		}
	}

	// ---------- зарплата ----------

	private static void paySalary(CitizenEntity citizen, Settlement settlement) {
		citizen.setSalaryTimer(citizen.getSalaryTimer() + Math.max(1, Rl2dopConfig.brainIntervalTicks));
		if (citizen.getSalaryTimer() < Rl2dopConfig.salaryPeriodTicks) {
			return;
		}
		citizen.setSalaryTimer(0);
		int salary = settlement.salaryFor(citizen.getProfession());
		if (salary <= 0) {
			return;
		}
		if (Rl2dopConfig.infiniteNpcMoney) {
			// временно: зарплаты платятся всегда, казна не ограниченна
			citizen.addWallet(salary);
			citizen.setMood(citizen.getMood() + 1);
			return;
		}
		if (settlement.treasury >= salary) {
			settlement.treasury -= salary;
			citizen.addWallet(salary);
			citizen.setMood(citizen.getMood() + 1);
		} else {
			citizen.setMood(citizen.getMood() - 5);
		}
	}

	// ---------- автосмена профессии ----------

	private static void maybeAutoSwitch(CitizenEntity citizen, ServerLevel level, Settlement settlement) {
		if (citizen.getProfessionCooldown() > 0) {
			citizen.setProfessionCooldown(citizen.getProfessionCooldown() - Math.max(1, Rl2dopConfig.brainIntervalTicks));
			return;
		}
		Profession current = citizen.getProfession();
		if (!current.isWorker()) {
			return;
		}
		List<Profession> vacancies = new ArrayList<>();
		for (Profession profession : Profession.values()) {
			if (profession == current || !profession.isWorker()) {
				continue;
			}
			if (settlement.jobCount(profession) == 0) {
				vacancies.add(profession);
			}
		}
		// автосмена только когда есть минимум 2–3 конкурирующие вакансии
		if (vacancies.size() < 2) {
			return;
		}
		Profession best = null;
		double bestScore = 0.0D;
		for (Profession profession : vacancies) {
			double score = score(settlement, profession);
			if (score > bestScore) {
				bestScore = score;
				best = profession;
			}
		}
		if (best == null) {
			return;
		}
		double currentScore = score(settlement, current);
		if (bestScore < currentScore * Rl2dopConfig.autoProfessionGain) {
			return;
		}
		citizen.setProfession(best);
		citizen.setWorkPos(SettlementService.workSpot(level, settlement, best));
		citizen.setProfessionCooldown(Rl2dopConfig.autoProfessionCooldownTicks);
		EconomyState.get(level.getServer())
				.log("NPC " + citizen.getCitizenName() + " сменил профессию на " + best.id());
	}

	private static double score(Settlement settlement, Profession profession) {
		double salary = settlement.salaryFor(profession);
		double demand = profession.produces() ? settlement.demand(profession.produceItem()) : 0.0D;
		return salary * (1.0D + demand / 100.0D);
	}

	// ---------- утилиты ----------

	public static void walkTo(CitizenEntity citizen, BlockPos pos) {
		citizen.getNavigation().moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 0.55D);
	}

	public static boolean isNight(ServerLevel level) {
		long time = level.getDayTime() % 24000L;
		return time >= 13000L && time < 23000L;
	}
}
