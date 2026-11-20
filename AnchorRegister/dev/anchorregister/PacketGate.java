package dev.anchorregister;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import io.papermc.paper.connection.PaperCommonConnection;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

final class PacketGate {
    private static final String HANDLER = "anchor_register";
    private static final String GAME_PACKETS = "net.minecraft.network.protocol.game";
    private static final Set<String> ALLOWED = Set.of(
        "ServerboundAcceptTeleportationPacket", "ServerboundChatAckPacket", "ServerboundChatCommandPacket",
        "ServerboundChatCommandSignedPacket", "ServerboundChatSessionUpdatePacket", "ServerboundChunkBatchReceivedPacket",
        "ServerboundClientTickEndPacket", "ServerboundPlayerLoadedPacket"
    );

    private final Field configurationHandle;
    private final Set<Channel> locked = ConcurrentHashMap.newKeySet();
    private volatile boolean network;

    void allowNetwork() {
        network = true;
    }

    PacketGate() throws ReflectiveOperationException {
        configurationHandle = PaperCommonConnection.class.getDeclaredField("handle");
        configurationHandle.setAccessible(true);
    }

    void lock(PlayerConfigurationConnection configuration) throws ReflectiveOperationException {
        ServerCommonPacketListenerImpl listener = (ServerCommonPacketListenerImpl) configurationHandle.get(configuration);
        lock(listener.connection.channel);
    }

    MovementGate lock(Player player) {
        return lock(((CraftPlayer) player).getHandle().connection.connection.channel);
    }

    private MovementGate lock(Channel channel) {
        if (channel.pipeline().get(HANDLER) instanceof MovementGate movement) return movement;
        MovementGate movement = new MovementGate();
        if (channel.pipeline().get(HANDLER) == null) {
            channel.pipeline().addBefore("packet_handler", HANDLER, movement);
            locked.add(channel);
            if (!channel.isActive()) {
                locked.remove(channel);
            }
        } else {
            throw new IllegalStateException("Имя сетевого обработчика AnchorRegister уже занято");
        }
        return movement;
    }

    final class MovementGate extends ChannelDuplexHandler {
        private final AtomicBoolean correction = new AtomicBoolean();
        private volatile Vec3 anchor;
        private long nextCorrection;
        private volatile boolean verification;

        void allowVerification(boolean allowed) {
            verification = allowed;
        }

        @Override
        public void write(ChannelHandlerContext context, Object packet, ChannelPromise promise) {
            if (packet instanceof ClientboundBlockDestructionPacket) {
                ReferenceCountUtil.release(packet);
                promise.setSuccess();
                return;
            }
            context.write(packet, promise);
        }

        void correct(Player player, long tick) {
            var handle = ((CraftPlayer) player).getHandle();
            Vec3 position = handle.position();
            anchor = position;
            if (tick >= nextCorrection && handle.connection.hasClientLoaded() && correction.getAndSet(false)) {
                handle.connection.internalTeleport(position.x, position.y, position.z, handle.getYRot(), handle.getXRot());
                nextCorrection = tick + Math.clamp((player.getPing() + 49L) / 50 + 1, 2, 20);
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext context, Object packet) {
            if (network && packet instanceof ServerboundCustomPayloadPacket payload) {
                String channel = payload.payload().type().id().toString();
                if (channel.equals(NetworkBridge.CHANNEL) || channel.equals("minecraft:register")
                    || channel.equals("minecraft:unregister")) {
                    context.fireChannelRead(packet);
                    return;
                }
            }
            if (packet instanceof ServerboundMovePlayerPacket move) {
                if (!move.hasPosition()) {
                    context.fireChannelRead(move);
                    return;
                }
                Vec3 current = anchor;
                if (current != null && !correction.get()
                    && (move.getX(current.x) != current.x || move.getY(current.y) != current.y || move.getZ(current.z) != current.z)) {
                    correction.set(true);
                }
                if (move.hasRotation()) {
                    context.fireChannelRead(new ServerboundMovePlayerPacket.Rot(
                        move.getYRot(0), move.getXRot(0), move.isOnGround(), move.horizontalCollision()));
                }
                return;
            }
            Class<?> type = packet.getClass();
            if (verification && packet instanceof ServerboundPlayerActionPacket action) {
                switch (action.getAction()) {
                    case START_DESTROY_BLOCK, ABORT_DESTROY_BLOCK, STOP_DESTROY_BLOCK -> context.fireChannelRead(packet);
                    default -> ReferenceCountUtil.release(packet);
                }
                return;
            }
            if (verification && (type.getSimpleName().equals("ServerboundSwingPacket")
                || type.getSimpleName().equals("ServerboundSetCarriedItemPacket"))) {
                context.fireChannelRead(packet);
                return;
            }
            if (packet instanceof ServerboundCustomPayloadPacket
                || (type.getPackageName().equals(GAME_PACKETS) && !ALLOWED.contains(type.getSimpleName()))) {
                ReferenceCountUtil.release(packet);
                return;
            }
            context.fireChannelRead(packet);
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) throws Exception {
            locked.remove(context.channel());
            super.channelInactive(context);
        }
    }

    void unlock(Player player) {
        unlock(((CraftPlayer) player).getHandle().connection.connection.channel);
    }

    private void unlock(Channel channel) {
        if (channel.pipeline().get(HANDLER) != null) {
            channel.pipeline().remove(HANDLER);
        }
        locked.remove(channel);
    }

    void unlockAll() {
        locked.forEach(this::unlock);
    }
}
