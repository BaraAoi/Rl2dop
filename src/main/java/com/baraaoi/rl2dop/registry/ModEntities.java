package com.baraaoi.rl2dop.registry;

import com.baraaoi.rl2dop.Rl2dop;
import com.baraaoi.rl2dop.entity.CitizenEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntities {
	private ModEntities() {
	}

	public static EntityType<CitizenEntity> CITIZEN;

	public static void init() {
		// В 1.21.1 Builder#build(String) проверяет ванильный datafixer и ругается в лог
		// на любой модовый id. На время регистрации проверку отключаем (как это делают
		// другие моды); на сохранения это не влияет.
		boolean previous = SharedConstants.CHECK_DATA_FIXER_SCHEMA;
		SharedConstants.CHECK_DATA_FIXER_SCHEMA = false;
		try {
			CITIZEN = Registry.register(
					BuiltInRegistries.ENTITY_TYPE,
					Rl2dop.id("citizen"),
					EntityType.Builder.<CitizenEntity>of(CitizenEntity::new, MobCategory.CREATURE)
							.sized(0.6F, 1.8F)
							.clientTrackingRange(12)
							.updateInterval(3)
							.build("citizen"));
			FabricDefaultAttributeRegistry.register(CITIZEN, CitizenEntity.createCitizenAttributes());
		} finally {
			SharedConstants.CHECK_DATA_FIXER_SCHEMA = previous;
		}
	}
}
