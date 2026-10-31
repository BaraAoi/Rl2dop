package com.baraaoi.rl2dop.entity;

import com.baraaoi.rl2dop.Rl2dopConfig;
import com.baraaoi.rl2dop.economy.EconomyState;
import com.baraaoi.rl2dop.economy.MarketMath;
import com.baraaoi.rl2dop.economy.Settlement;
import com.baraaoi.rl2dop.economy.SettlementService;
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
		if (isNight(level)) {
			handleSleep(citizen);
		} else {
			handleWork(citizen, level, settlement);
		}
		paySalary(citizen, settlement);
		maybeAutoSwitch(citizen, level, settlement);
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

	private static void handleSleep(CitizenEntity citizen) {
		citizen.setCitizenState(CitizenState.SLEEPING);
		BlockPos home = citizen.getHomePos();
		if (home == null) {
			citizen.getNavigation().stop();
			return;
		}
		if (citizen.blockPosition().distSqr(home) > 4.0D) {
			if (citizen.getNavigation().isDone()) {
				walkTo(citizen, home);
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
		if (citizen.blockPosition().distSqr(work) > 4.0D) {
			citizen.setCitizenState(CitizenState.WALKING);
			if (citizen.getNavigation().isDone()) {
				walkTo(citizen, work);
			}
			return;
		}
		citizen.getNavigation().stop();
		citizen.setCitizenState(CitizenState.WORKING);

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
			} else if (settlement.stock(input) >= need) {
				settlement.takeStock(input, need);
			} else {
				// нет сырья — ждём поставки
				return;
			}
		}
		if (profession.produces()) {
			settlement.addStock(profession.produceItem(), profession.produceAmount());
			settlement.addDemand(profession.produceItem(), -1);
		}
		if (profession == Profession.BUILDER) {
			settlement.buildProgress = Math.min(100, settlement.buildProgress + 1);
		}
		if (profession == Profession.MINER && citizen.getRandom().nextInt(4) == 0) {
			settlement.addStock("minecraft:iron_ingot", 1);
		}
		if (profession == Profession.HEALER) {
			healNearby(citizen, level);
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
