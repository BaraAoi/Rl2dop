package com.baraaoi.rl2dop.item;

import com.baraaoi.rl2dop.entity.CitizenEntity;
import com.baraaoi.rl2dop.economy.SettlementService;
import com.baraaoi.rl2dop.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;

/** Яйцо призыва NPC (своя реализация, чтобы не зависеть от ванильного SpawnEggItem). */
public class NpcEggItem extends Item {
	public NpcEggItem(Item.Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (!(context.getLevel() instanceof ServerLevel level)) {
			return InteractionResult.SUCCESS;
		}
		BlockPos pos = context.getClickedPos().relative(context.getClickedFace());
		CitizenEntity citizen = ModEntities.CITIZEN.create(level);
		if (citizen == null) {
			return InteractionResult.FAIL;
		}
		citizen.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, level.random.nextFloat() * 360.0F, 0.0F);
		SettlementService.prepareCitizen(level, citizen, pos);
		level.addFreshEntity(citizen);
		if (context.getPlayer() instanceof ServerPlayer player && !player.getAbilities().instabuild) {
			ItemStack stack = context.getItemInHand();
			stack.shrink(1);
		}
		return InteractionResult.CONSUME;
	}
}
