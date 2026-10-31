package com.baraaoi.rl2dop.client;

import com.baraaoi.rl2dop.net.ShopPayload;
import com.baraaoi.rl2dop.net.SyncPayload;
import com.baraaoi.rl2dop.net.WalletPayload;
import com.baraaoi.rl2dop.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

public class Rl2dopClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(ModEntities.CITIZEN, CitizenRenderer::new);
		HudRenderCallback.EVENT.register(EconomyHud::render);

		ClientPlayNetworking.registerGlobalReceiver(SyncPayload.TYPE, (payload, context) ->
				context.client().execute(() -> ClientEconomy.update(payload)));
		ClientPlayNetworking.registerGlobalReceiver(ShopPayload.TYPE, (payload, context) ->
				context.client().execute(() -> ShopScreen.handle(payload)));
		ClientPlayNetworking.registerGlobalReceiver(WalletPayload.TYPE, (payload, context) ->
				context.client().execute(() -> {
					ClientEconomy.update(payload);
					WalletScreen.handleOpen();
				}));
	}
}
