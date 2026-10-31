package com.baraaoi.rl2dop.registry;

import com.baraaoi.rl2dop.entity.CitizenEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntities {
	private ModEntities() {
	}

	public static final EntityType<CitizenEntity> CITIZEN = Registry.register(
			BuiltInRegistries.ENTITY_TYPE,
			com.baraaoi.rl2dop.Rl2dop.id("citizen"),
			EntityType.Builder.<CitizenEntity>of(CitizenEntity::new, MobCategory.CREATURE)
					.sized(0.6F, 1.8F)
					.clientTrackingRange(12)
					.updateInterval(3)
					.build("citizen"));

	public static void init() {
		FabricDefaultAttributeRegistry.register(CITIZEN, CitizenEntity.createCitizenAttributes());
	}
}
