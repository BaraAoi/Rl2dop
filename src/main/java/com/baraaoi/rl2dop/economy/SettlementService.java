package com.baraaoi.rl2dop.economy;

import com.baraaoi.rl2dop.Rl2dopConfig;
import com.baraaoi.rl2dop.entity.CitizenEntity;
import com.baraaoi.rl2dop.entity.Profession;
import com.baraaoi.rl2dop.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

/** Поиск/создание поселений, спавн NPC, привязка к ванильным деревням. */
public final class SettlementService {
	private SettlementService() {
	}

	public static Settlement byId(ServerLevel level, String id) {
		if (level == null || id == null || id.isEmpty()) {
			return null;
		}
		return EconomyState.get(level.getServer()).settlement(id);
	}

	public static Settlement nearest(ServerLevel level, BlockPos pos) {
		EconomyState state = EconomyState.get(level.getServer());
		String dimension = level.dimension().location().toString();
		Settlement best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Settlement settlement : state.all()) {
			if (!dimension.equals(settlement.dimension)) {
				continue;
			}
			double distance = settlement.hall().distSqr(pos);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = settlement;
			}
		}
		if (best != null && bestDistance <= (double) Rl2dopConfig.settlementRadius * Rl2dopConfig.settlementRadius) {
			return best;
		}
		return bestDistance <= (double) Rl2dopConfig.settlementRadius * Rl2dopConfig.settlementRadius ? best : null;
	}

	public static Settlement forPlayer(ServerPlayer player) {
		Settlement settlement = nearest(player.serverLevel(), player.blockPosition());
		if (settlement != null) {
			return settlement;
		}
		EconomyState state = EconomyState.get(player.serverLevel().getServer());
		return state.defaultSettlement();
	}

	public static void onPlayerJoin(EconomyState state, ServerPlayer player) {
		if (state.settlements.isEmpty()) {
			BlockPos pos = player.blockPosition();
			state.createSettlement("Поселение", pos, player.serverLevel().dimension().location().toString());
		}
	}

	/** Подготовить NPC перед добавлением в мир: поселение, имя, точки дома/работы. */
	public static void prepareCitizen(ServerLevel level, CitizenEntity citizen, BlockPos pos) {
		EconomyState state = EconomyState.get(level.getServer());
		Settlement settlement = nearest(level, pos);
		if (settlement == null) {
			settlement = state.createSettlement("Поселение", pos, level.dimension().location().toString());
		}
		citizen.setSettlementId(settlement.id);
		if (citizen.getCitizenName().isEmpty()) {
			citizen.setCitizenName(EconomyState.randomName(level.random));
		}
		Profession profession = citizen.getProfession();
		citizen.setHomePos(jitter(level, pos, 6));
		citizen.setWorkPos(workSpot(level, settlement, profession));
		citizen.setShopPos(settlement.shop());
		citizen.setWallet(Math.max(citizen.getWallet(), 20));
		citizen.setProfessionCooldown(Rl2dopConfig.autoProfessionCooldownTicks);
		settlement.registerNpc(citizen.getUUID());
		citizen.refreshNameTag();
		state.setDirty();
	}

	public static CitizenEntity spawnCitizen(ServerLevel level, Settlement settlement, Profession profession,
											 BlockPos pos, String name) {
		CitizenEntity citizen = ModEntities.CITIZEN.create(level);
		if (citizen == null) {
			return null;
		}
		citizen.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, level.random.nextFloat() * 360.0F, 0.0F);
		citizen.setProfession(profession);
		citizen.setCitizenName(name != null && !name.isBlank() ? name : EconomyState.randomName(level.random));
		// не даём свежеспавненному NPC сразу бежать менять профессию
		citizen.setProfessionCooldown(Rl2dopConfig.autoProfessionCooldownTicks);
		citizen.setSettlementId(settlement.id);
		citizen.setHomePos(jitter(level, settlement.hall(), 8));
		citizen.setWorkPos(workSpot(level, settlement, profession));
		citizen.setShopPos(settlement.shop());
		citizen.setWallet(30);
		settlement.registerNpc(citizen.getUUID());
		citizen.refreshNameTag();
		level.addFreshEntity(citizen);
		EconomyState.get(level.getServer()).setDirty();
		return citizen;
	}

	public static BlockPos workSpot(ServerLevel level, Settlement settlement, Profession profession) {
		BlockPos hall = settlement.hall();
		double angle = profession.ordinal() * (Math.PI * 2.0D / Profession.values().length);
		int x = hall.getX() + (int) Math.round(Math.cos(angle) * 7.0D);
		int z = hall.getZ() + (int) Math.round(Math.sin(angle) * 7.0D);
		return surface(level, x, z, hall.getY());
	}

	public static BlockPos jitter(ServerLevel level, BlockPos center, int radius) {
		int x = center.getX() + level.random.nextInt(radius * 2 + 1) - radius;
		int z = center.getZ() + level.random.nextInt(radius * 2 + 1) - radius;
		return surface(level, x, z, center.getY());
	}

	/** Точка на поверхности земли — чтобы ратуша не проваливалась и не висела в воздухе. */
	public static BlockPos surface(ServerLevel level, int x, int z, int fallbackY) {
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		if (y <= level.getMinBuildHeight()) {
			y = fallbackY;
		}
		return new BlockPos(x, y, z);
	}

	/** Привязка к ванильной деревне: ищем колокол, ставим там ратушу и спавним стартовых NPC. */
	public static String bindVillage(ServerLevel level, BlockPos origin, String requestedName) {
		BlockPos bell = findBell(level, origin, 48);
		BlockPos hallPos = bell != null ? bell : origin;
		String name = requestedName != null && !requestedName.isBlank()
				? requestedName
				: "Деревня " + hallPos.getX() + "," + hallPos.getZ();
		EconomyState state = EconomyState.get(level.getServer());
		Settlement settlement = state.createSettlement(name, hallPos, level.dimension().location().toString());
		settlement.treasury = 600L;

		BlockPos spawnBase = hallPos.above();
		List<String> spawned = new ArrayList<>();
		Profession[] starters = {Profession.FARMER, Profession.TRADER, Profession.GUARD, Profession.COOK};
		for (Profession profession : starters) {
			CitizenEntity citizen = spawnCitizen(level, settlement, profession, spawnBase, null);
			if (citizen != null) {
				spawned.add(citizen.getCitizenName() + " (" + profession.id() + ")");
			}
		}
		Displays.ensureBoard(level, settlement, hallPos.offset(0, 2, 0));
		SignBoards.install(level, settlement);
		SettlementBuilds.build(level, settlement, Rl2dopConfig.buildBlocksPerTick);
		state.log("привязка к ванильной деревне: " + name + " (" + (bell != null ? "колокол" : "точка игрока") + ")");
		return "Поселение «" + settlement.displayName() + "» создано в " + hallPos.toShortString()
				+ (bell != null ? " (у колокола)" : "") + ". NPC: " + String.join(", ", spawned);
	}

	private static BlockPos findBell(ServerLevel level, BlockPos origin, int radius) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dx = -radius; dx <= radius; dx += 1) {
			for (int dz = -radius; dz <= radius; dz += 1) {
				for (int dy = -12; dy <= 12; dy += 1) {
					cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
					if (level.getBlockState(cursor).is(Blocks.BELL)) {
						return cursor.immutable();
					}
				}
			}
		}
		return null;
	}

	/** Ближайший NPC к игроку (для команд и взаимодействия). */
	public static CitizenEntity nearestCitizen(ServerLevel level, Player player, double radius) {
		CitizenEntity best = null;
		double bestDistance = radius * radius;
		for (CitizenEntity citizen : level.getEntitiesOfClass(CitizenEntity.class, player.getBoundingBox().inflate(radius))) {
			double distance = citizen.distanceToSqr(player);
			if (distance <= bestDistance) {
				bestDistance = distance;
				best = citizen;
			}
		}
		return best;
	}
}
