package com.baraaoi.rl2dop.net;

import com.baraaoi.rl2dop.Rl2dop;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** C2S: 0 = положить всё в кошелёк, 1 = достать всё. */
public record WalletActionPayload(int action) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<WalletActionPayload> TYPE =
			new CustomPacketPayload.Type<>(Rl2dop.id("wallet_action"));

	// один int — буфер ByteBuf подходит: он шире, чем RegistryFriendlyByteBuf
	public static final StreamCodec<ByteBuf, WalletActionPayload> CODEC =
			ByteBufCodecs.VAR_INT.map(WalletActionPayload::new, WalletActionPayload::action);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
