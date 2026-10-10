package com.stakiran.gezan;

import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// ボスバーに 経過時間、Y座標、地上到達カウントダウン、規模 をまとめて表示する
// Y座標とカウントダウンがプレイヤーごとに異なるため、ボスバーはプレイヤーごとに持つ
// バーの長さは下山の進捗（開始地点 -> 地上）
public class HudManager {

    private static final int TICKS_PER_SECOND = 20;

    private static final Map<UUID, ServerBossBar> bossBars = new HashMap<>();

    public static void hide() {
        for (ServerBossBar bar : bossBars.values()) {
            bar.clearPlayers();
        }
        bossBars.clear();
    }

    public static void update(MinecraftServer server, int gameTicks, int startY, int groundY,
            Map<UUID, Integer> groundTicks, int winTicks, int scale) {
        String timeText = "§f経過 §b" + formatTime(gameTicks / TICKS_PER_SECOND);

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerBossBar bar = bossBars.computeIfAbsent(player.getUuid(),
                uuid -> new ServerBossBar(Text.literal(""), BossBar.Color.BLUE, BossBar.Style.PROGRESS));
            // Covers players who joined after setup or reconnected (new player object)
            if (!bar.getPlayers().contains(player)) {
                bar.clearPlayers();
                bar.addPlayer(player);
            }

            int y = player.getBlockPos().getY();
            int onGround = groundTicks.getOrDefault(player.getUuid(), 0);

            String groundText;
            BossBar.Color color;
            if (onGround > 0) {
                int secondsLeft = (winTicks - onGround + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND;
                groundText = "§a地上到達！ 勝利まで " + secondsLeft + " 秒";
                color = BossBar.Color.GREEN;
            } else {
                groundText = "§7地上 (y<=" + groundY + ") まで " + Math.max(0, y - groundY);
                color = BossBar.Color.BLUE;
            }
            String yText = "§fY: " + (y <= groundY ? "§a" : "§b") + y;

            float percent = startY <= groundY ? 1.0f
                : (float) (startY - y) / (startY - groundY);
            percent = Math.max(0.0f, Math.min(1.0f, percent));

            // setName/setColor/setPercent only send packets when the value changes
            bar.setName(Text.literal(timeText + " §8| " + yText + " §8| " + groundText + " §8| §7規模 " + scale));
            bar.setColor(color);
            bar.setPercent(percent);
        }
    }

    public static String formatTime(int seconds) {
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    public static void showTitle(MinecraftServer server, String title, String subtitle) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(subtitle)));
            player.networkHandler.sendPacket(new TitleS2CPacket(Text.literal(title)));
        }
    }
}
