package com.baraaoi.rl2dop.client;

import com.baraaoi.rl2dop.entity.CitizenEntity;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

/** Гуманоидный рендер: имя, профессия в нике, меч/лук/инструмент в руке. */
public class CitizenRenderer extends HumanoidMobRenderer<CitizenEntity, PlayerModel<CitizenEntity>> {
	private static final ResourceLocation TEXTURE =
			ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png");

	public CitizenRenderer(EntityRendererProvider.Context context) {
		super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
	}

	@Override
	public ResourceLocation getTextureLocation(CitizenEntity entity) {
		return TEXTURE;
	}

}
