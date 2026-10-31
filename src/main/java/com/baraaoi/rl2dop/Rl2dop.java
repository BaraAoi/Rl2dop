package com.baraaoi.rl2dop;

import com.baraaoi.rl2dop.command.Rl2dopCommands;
import com.baraaoi.rl2dop.economy.EconomyState;
import com.baraaoi.rl2dop.economy.EconomyTicker;
import com.baraaoi.rl2dop.economy.SettlementService;
import com.baraaoi.rl2dop.net.ModPackets;
import com.baraaoi.rl2dop.registry.ModEntities;
import com.baraaoi.rl2dop.registry.ModItems;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Rl2dop implements ModInitializer {
	public static final String MOD_ID = "rl2dop";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		Rl2dopConfig.load();

		ModEntities.init();
		ModItems.init();
		ModPackets.init();

		Rl2dopCommands.register();

		ServerTickEvents.END_SERVER_TICK.register(EconomyTicker::onServerTick);

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.getPlayer();
			EconomyState state = EconomyState.get(server);
			SettlementService.onPlayerJoin(state, player);
			ModPackets.syncTo(player);
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				ModPackets.clearPlayer(handler.getPlayer().getUUID()));

		LOGGER.info("[rl2dop] экономика загружена");
	}
}
