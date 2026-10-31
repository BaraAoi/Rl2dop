package com.baraaoi.rl2dop.economy;

import com.baraaoi.rl2dop.Rl2dopConfig;
import com.baraaoi.rl2dop.entity.CitizenBrain;
import com.baraaoi.rl2dop.entity.CitizenEntity;
import com.baraaoi.rl2dop.entity.CitizenState;
import com.baraaoi.rl2dop.entity.Profession;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * Жизнь деревни: вакансии, самострой и переселенцы.
 *
 * <p>Устройство взято с ванильных жителей: у деревни есть место встреч (у нас это таверна, а пока
 * её нет — площадь у колокола), без дома человек ютится там, а как только появляется вакансия или
 * материал на дом — дело делается само, без команды игрока. Шаг вызывается раз в цикл рынка, а не
 * каждый тик: посёлок живёт «по расписанию», а не по одному тиковому щелчку.
 */
public final class VillageLife {
	private VillageLife() {
	}

	/** Кого поселение нанимает в первую очередь (право на вакансию). */
	private static final Profession[] HIRE_ORDER = {Profession.FARMER, Profession.MINER, Profession.LUMBERJACK,
			Profession.BUILDER, Profession.COOK, Profession.CRAFTSMAN, Profession.RANCHER, Profession.HEALER,
			Profession.TRADER, Profession.GUARD};

	public static void step(MinecraftServer server, EconomyState state) {
		for (Settlement settlement : state.all()) {
			if (settlement.buildProgress < 100) {
				continue;   // сначала ратуша, участки и улицы — потом уже жизнь
			}
			ServerLevel level = Displays.levelOf(server, settlement.dimension);
			if (level == null || !level.hasChunkAt(settlement.hall())) {
				continue;
			}
			housingDemand(level, settlement, state);
			fillVacancies(level, settlement, state);
			immigrants(level, settlement, state);
		}
	}

	/** Все жители поселения, которые сейчас в мире. */
	public static List<CitizenEntity> citizens(ServerLevel level, Settlement settlement) {
		List<CitizenEntity> out = new ArrayList<>();
		for (String raw : new ArrayList<>(settlement.npcs)) {
			net.minecraft.world.entity.Entity entity;
			try {
				entity = level.getEntity(java.util.UUID.fromString(raw));
			} catch (IllegalArgumentException e) {
				continue;
			}
			if (entity instanceof CitizenEntity citizen && !citizen.isRemoved()) {
				out.add(citizen);
			}
		}
		return out;
	}

	/** Кого не хватает: постройка есть, а работника нет; строителя просят бездомные. */
	public static List<Profession> vacancies(Settlement settlement) {
		List<Profession> out = new ArrayList<>();
		int room = Math.min(Rl2dopConfig.maxVillageHouses, SettlementBuilds.plotCount());
		for (Profession profession : HIRE_ORDER) {
			if (settlement.jobCount(profession) > 0) {
				continue;
			}
			boolean hasSite = settlement.workSites.containsKey(profession.id());
			boolean houseNeeded = profession == Profession.BUILDER && Rl2dopConfig.selfBuildHouses
					&& settlement.buildDesire > 0 && settlement.houses.size() < room;
			boolean townNeeds = settlement.npcs.size() >= 4
					&& (profession == Profession.COOK || profession == Profession.TRADER
							|| profession == Profession.GUARD);
			if (hasSite || houseNeeded || townNeeds) {
				out.add(profession);
			}
		}
		return out;
	}

	/** Сколько вакансий у поселения (для таблички у таверны и /market_dump). */
	public static int vacancyCount(Settlement settlement) {
		return vacancies(settlement).size();
	}

	/** Безработные у таверны расходятся по вакансиям — раньше тот, кому платят больше нужды. */
	private static void fillVacancies(ServerLevel level, Settlement settlement, EconomyState state) {
		List<Profession> free = vacancies(settlement);
		if (free.isEmpty()) {
			return;
		}
		for (CitizenEntity citizen : citizens(level, settlement)) {
			if (!citizen.getProfession().isJobless() || free.isEmpty()) {
				continue;
			}
			Profession best = bestOf(settlement, free);
			if (best == null) {
				break;
			}
			free.remove(best);
			citizen.setProfession(best);
			citizen.setWorkPos(SettlementService.workSpot(level, settlement, best));
			citizen.setProfessionCooldown(Rl2dopConfig.autoProfessionCooldownTicks);
			citizen.setCitizenState(CitizenState.WALKING);
			citizen.refreshNameTag();
			state.log("поселение " + settlement.id + ": " + citizen.getCitizenName() + " дождался вакансии и стал: "
					+ best.id());
		}
	}

	/** Самая выгодная поселению вакансия (зарплата × спрос на продукт, плюс срочная стройка). */
	private static Profession bestOf(Settlement settlement, List<Profession> options) {
		Profession best = null;
		double bestScore = -1.0D;
		for (Profession profession : options) {
			double demand = profession.produces() ? settlement.demand(profession.produceItem()) : 0.0D;
			double score = settlement.salaryFor(profession) * (1.0D + demand / 100.0D);
			if (profession == Profession.BUILDER && settlement.buildDesire > 0) {
				score += 40.0D + settlement.buildDesire * 4.0D;
			}
			if (score > bestScore) {
				bestScore = score;
				best = profession;
			}
		}
		return best;
	}

	/**
	 * Поселение само хочет строить дома: считаем, скольким жителям негде жить, поднимаем спрос на
	 * камень и брёвна и выбираем участок из плана. Дальше стройку ведёт строитель (buildGoal).
	 */
	private static void housingDemand(ServerLevel level, Settlement settlement, EconomyState state) {
		int homeless = 0;
		int jobless = 0;
		for (CitizenEntity citizen : citizens(level, settlement)) {
			if (citizen.isMayor()) {
				continue;
			}
			if (citizen.getProfession().isJobless()) {
				jobless++;
			}
			BlockPos home = citizen.getHomePos();
			if (home == null || !settlement.hasHouseNear(home)) {
				homeless++;
			}
		}
		if (settlement.buildDesire != homeless || settlement.joblessCount != jobless) {
			settlement.buildDesire = homeless;
			settlement.joblessCount = jobless;
			state.setDirty();
		}
		if (homeless > 0) {
			// «хотим строить» видно и рынку, и табличке нужд: спрос на материал растёт сам
			settlement.addDemand("minecraft:cobblestone", Math.min(24, 3 + homeless * 2));
			settlement.addDemand("minecraft:oak_log", Math.min(20, 2 + homeless * 2));
		}
		if (!Rl2dopConfig.selfBuildHouses) {
			return;
		}
		int room = Math.min(Rl2dopConfig.maxVillageHouses, SettlementBuilds.plotCount());
		BlockPos goal = settlement.houseGoalPos();
		if (goal != null && (homeless == 0 || settlement.hasHouseNear(goal) || settlement.houses.size() >= room)) {
			settlement.setHouseGoal(null);
			state.setDirty();
		}
		if (settlement.hasHouseGoal() || homeless <= 0 || settlement.houses.size() >= room) {
			return;
		}
		BlockPos plot = SettlementBuilds.freePlot(level, settlement);
		if (plot == null) {
			return;
		}
		settlement.setHouseGoal(plot);
		state.log("поселение " + settlement.id + ": " + homeless + " жител(ям) негде жить — выбрали участок под дом "
				+ plot.toShortString());
		// если строителя нет, за топор садится тот, кто ждёт работу у таверны
		for (CitizenEntity citizen : citizens(level, settlement)) {
			if (!citizen.getProfession().isJobless()) {
				continue;
			}
			citizen.setProfession(Profession.BUILDER);
			citizen.setWorkPos(SettlementBuilds.safeStand(level, plot));
			citizen.refreshNameTag();
			state.log("поселение " + settlement.id + ": " + citizen.getCitizenName()
					+ " записался в строители и идёт на участок " + plot.toShortString());
			break;
		}
		state.setDirty();
	}

	/**
	 * Строитель доводит задуманную поселением стройку до конца: он на площадке, материал на складе —
	 * дом готов. Вызывается из {@code CitizenBrain} в рабочем цикле, поэтому у NPC есть на это время.
	 */
	public static void buildGoal(ServerLevel level, Settlement settlement, CitizenEntity builder) {
		if (!Rl2dopConfig.selfBuildHouses || !settlement.hasHouseGoal()) {
			return;
		}
		BlockPos goal = settlement.houseGoalPos();
		if (goal == null) {
			settlement.setHouseGoal(null);
			return;
		}
		if (!level.hasChunkAt(goal)) {
			return;
		}
		if (builder.blockPosition().distSqr(goal) > 64.0D) {
			builder.setWorkPos(SettlementBuilds.safeStand(level, goal));
			return;
		}
		int cobble = Math.max(0, Rl2dopConfig.houseCostCobble);
		int logs = Math.max(0, Rl2dopConfig.houseCostLogs);
		if (settlement.stock("minecraft:cobblestone") < cobble || settlement.stock("minecraft:oak_log") < logs) {
			settlement.addDemand("minecraft:cobblestone", 4);
			settlement.addDemand("minecraft:oak_log", 4);
			return;
		}
		settlement.takeStock("minecraft:cobblestone", cobble);
		settlement.takeStock("minecraft:oak_log", logs);
		BlockPos built = SettlementBuilds.buildHouse(level, settlement, goal);
		settlement.setHouseGoal(null);
		if (built != null) {
			SettlementService.assignHomes(level, settlement);
			SignBoards.refresh(level, settlement);
			EconomyState.get(level.getServer()).log("поселение " + settlement.id + ": строитель поставил дом в "
					+ built.toShortString() + ", домов теперь " + settlement.houses.size());
		}
		EconomyState.get(level.getServer()).setDirty();
	}

	/** Принудительно принять N переселенцев (команда /settle_settlers, отладка, CI). */
	public static int invite(ServerLevel level, Settlement settlement, EconomyState state, int count) {
		int spawned = 0;
		for (int i = 0; i < Math.max(1, Math.min(8, count)); i++) {
			List<Profession> free = vacancies(settlement);
			Profession profession = free.isEmpty() ? Profession.UNEMPLOYED : bestOf(settlement, free);
			if (profession == null) {
				profession = Profession.UNEMPLOYED;
			}
			String name = EconomyState.randomName(level.random);
			CitizenEntity settler = SettlementService.spawnCitizen(level, settlement, profession,
					SettlementService.gatherPoint(level, settlement), name);
			if (settler == null) {
				break;
			}
			if (profession.isJobless()) {
				settler.setCitizenState(CitizenState.WAITING);
			}
			state.log("в поселение " + settlement.id + " прибыл " + name + " ("
					+ (profession.isJobless() ? "ждёт работу у таверны" : "сразу на работу: " + profession.id()) + ")");
			spawned++;
		}
		settlement.lastSettlerTick = level.getGameTime();
		SignBoards.refresh(level, settlement);
		state.setDirty();
		return spawned;
	}

	/** Приезжие: приходят сами — сначала на незанятые мастерские, потом «просто так», в таверну. */
	private static void immigrants(ServerLevel level, Settlement settlement, EconomyState state) {
		if (!Rl2dopConfig.immigration) {
			return;
		}
		int population = settlement.npcs.size();
		if (Rl2dopConfig.maxSettlers > 0 && population >= Rl2dopConfig.maxSettlers) {
			return;
		}
		if (population >= Rl2dopConfig.maxNpcsPerSettlement) {
			return;
		}
		List<Profession> free = vacancies(settlement);
		int beds = settlement.houses.size() * Math.max(1, Rl2dopConfig.villagersPerHouse)
				+ (settlement.hasTavern() ? 2 : 0);
		boolean room = beds > population;
		if (!room && free.isEmpty()) {
			return;
		}
		if (level.getGameTime() - settlement.lastSettlerTick < Rl2dopConfig.immigrationIntervalTicks) {
			return;
		}
		if (CitizenBrain.isNight(level)) {
			return;   // странники приходят при свете дня
		}
		settlement.lastSettlerTick = level.getGameTime();
		Profession profession = free.isEmpty() ? Profession.UNEMPLOYED : bestOf(settlement, free);
		BlockPos at = SettlementService.gatherPoint(level, settlement);
		String name = EconomyState.randomName(level.random);
		CitizenEntity settler = SettlementService.spawnCitizen(level, settlement,
				profession == null ? Profession.UNEMPLOYED : profession, at, name);
		if (settler == null) {
			return;
		}
		if (profession == null || profession.isJobless()) {
			settler.setCitizenState(CitizenState.WAITING);
			state.log("в поселение " + settlement.id + " пришёл " + name + " — поселился в таверне и ждёт работу");
		} else {
			state.log("в поселение " + settlement.id + " приехал " + name + " — сразу на работу: " + profession.id());
		}
		SignBoards.refresh(level, settlement);
		state.setDirty();
	}
}
