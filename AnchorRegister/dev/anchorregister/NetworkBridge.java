package dev.anchorregister;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerRegisterChannelEvent;

final class NetworkBridge implements PluginMessageListener, Listener {
    static final String CHANNEL = "anchor:network";
    private final AnchorRegister plugin;
    private final String secret;
    private final Map<UUID, String> challenges = new HashMap<>();

    NetworkBridge(AnchorRegister plugin) {
        this.plugin = plugin;
        secret = plugin.getConfig().getString("network.secret", "");
        if (secret.length() < 32) throw new IllegalArgumentException("Не настроен ключ сети");
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void registered(PlayerRegisterChannelEvent event) {
        if (!event.getChannel().equals(CHANNEL)) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = event.getPlayer();
            if (!player.isOnline()) return;
            String challenge = challenges.get(player.getUniqueId());
            if (challenge != null) send(player, "check", challenge);
            if (!plugin.locked(player)) verified(player);
        });
    }

    void begin(Player player) {
        String nonce = UUID.randomUUID().toString();
        challenges.put(player.getUniqueId(), nonce);
        send(player, "check", nonce);
    }

    void forget(Player player) {
        challenges.remove(player.getUniqueId());
    }

    void verified(Player player) {
        send(player, "verified", "");
    }

    void route(Player player, String server) {
        send(player, "route", server);
    }

    private void send(Player player, String action, String value) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeUTF(action);
            output.writeUTF(value);
            player.sendPluginMessage(plugin, CHANNEL, bytes.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals(CHANNEL) || message.length > 1024) return;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(message))) {
            if (!input.readUTF().equals("resume")) return;
            String nonce = input.readUTF();
            String signature = input.readUTF();
            if (input.available() != 0 || !nonce.equals(challenges.get(player.getUniqueId()))) return;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal((player.getUniqueId() + ":" + nonce).getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(expected, HexFormat.of().parseHex(signature))) return;
            challenges.remove(player.getUniqueId());
            plugin.resumeFromNetwork(player);
        } catch (IOException | GeneralSecurityException | IllegalArgumentException ignored) {
            // Unauthenticated clients can send this channel, so malformed proofs are ignored.
        }
    }
}
