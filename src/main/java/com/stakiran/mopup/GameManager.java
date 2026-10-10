package com.stakiran.mopup;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Box;
import net.minecraft.world.GameMode;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.rule.GameRules;

import java.util.List;

public class GameManager {

    public enum Phase {
        IDLE,   // No game running
        GAME,   // Mopping up
        WON,    // Cleared
        LOST    // A player died
    }

    private static Phase currentPhase = Phase.IDLE;
    private static ServerWorld gameWorld;

    private static final int TICKS_PER_SECOND = 20;

    // Clear condition: no target mobs for 30 seconds
    private static final int CLEAR_SECONDS = 30;
    private static final int CLEAR_TICKS = CLEAR_SECONDS * TICKS_PER_SECOND;
    // -1 = countdown not running
    private static int clearTicksRemaining = -1;
    // Countdown starts only after at least one target has been seen,
    // so the game is not won instantly before any mob spawns
    private static boolean targetSeen;

    // Glow: 10 seconds every minute
    private static final int GLOW_CYCLE_TICKS = 60 * TICKS_PER_SECOND;
    private static final int GLOW_DURATION_TICKS = 10 * TICKS_PER_SECOND;

    private static int gameTicks;

    // Game scale measured at setup (see SpawnScanner.Result.scale)
    private static int scale;

    // Y threshold for "surface"
    private static final int Y_THRESHOLD = 64;
    // Going below this Y is game over (prevents hiding deep underground until the surface clears)
    private static final int Y_GAME_OVER = 60;
    // Going above (highest ground at setup + margin) is also game over: from high up,
    // mobs despawn and stop spawning, so the surface would clear by itself
    private static final int CEILING_MARGIN = 3;
    private static int ceilingY;

    // Score: 50 at the base time (scale / 10 minutes, at least MIN_BASE_SECONDS),
    // approaching 100 when faster and 0 when slower.
    // score = 100 / (1 + r^2), r = clear time / base time
    private static final double BASE_SECONDS_PER_SCALE = 60.0 / 10;
    // Small worlds (scale <= 30) share a fixed base time: winning takes at least
    // the clear countdown anyway, so a base time proportional to scale alone is meaningless there
    private static final int MIN_BASE_SECONDS = 3 * 60;

    private static final int BORDER_SIZE = 100;
    private static final long MIDNIGHT = 18000;

    // ========== SETUP ==========

    // Can be called at any time (also works as a reset)
    public static void setup(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return;

        MinecraftServer server = source.getServer();
        gameWorld = player.getEntityWorld();

        // Equivalent to the following commands. Done via API because commands dispatched
        // from inside another command (/mopup) are queued and never run.
        //   /worldborder center ~ ~
        //   /worldborder set 100
        //   /gamerule advance_time false
        //   /time set midnight
        WorldBorder border = gameWorld.getWorldBorder();
        border.setCenter(player.getX(), player.getZ());
        border.setSize(BORDER_SIZE);
        gameWorld.getGameRules().setValue(GameRules.ADVANCE_TIME, false, server);
        for (ServerWorld world : server.getWorlds()) {
            world.setTimeOfDay(MIDNIGHT);
        }

        // Reset player state (including inventory) so that a retry starts fair
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            p.changeGameMode(GameMode.SURVIVAL);
            KitManager.giveKit(p);
            p.setHealth(p.getMaxHealth());
            p.getHungerManager().setFoodLevel(20);
            p.getHungerManager().setSaturationLevel(5.0f);
            p.clearStatusEffects();
            p.extinguish();
            p.setExperienceLevel(0);
            p.setExperiencePoints(0);
        }

        currentPhase = Phase.GAME;
        gameTicks = 0;
        clearTicksRemaining = -1;
        targetSeen = false;

        HudManager.hide(); // Bars are (re)created on the next tick

        SpawnScanner.Result scan = SpawnScanner.scan(gameWorld, Y_THRESHOLD);
        scale = scan.scale();
        ceilingY = scan.maxSurfaceY() + CEILING_MARGIN;

        server.getPlayerManager().broadcast(
            Text.literal("§a§l[Mopup] ゲーム開始！ 地上 (y>=" + Y_THRESHOLD + ") の敵モブをすべて掃討せよ！ y" + Y_GAME_OVER + "〜" + ceilingY + " の外に出るとゲームオーバー"), false);
        server.getPlayerManager().broadcast(
            Text.literal(String.format("§e[Mopup] 規模: %d （屋外 %,d マス / 屋内・洞窟 %,d マス）",
                scale, scan.outdoor(), scan.indoor())), false);
    }

    // ========== TARGETS ==========

    private static List<Entity> findTargets() {
        WorldBorder border = gameWorld.getWorldBorder();
        Box area = new Box(
            border.getBoundWest(), Y_THRESHOLD, border.getBoundNorth(),
            border.getBoundEast(), gameWorld.getTopYInclusive() + 1, border.getBoundSouth());
        return gameWorld.getEntitiesByClass(Entity.class, area, TargetMobs::isTarget);
    }

    // ========== WIN ==========

    private static void win(MinecraftServer server) {
        currentPhase = Phase.WON;
        HudManager.hide();

        int seconds = gameTicks / TICKS_PER_SECOND;
        String time = formatTime(seconds);
        int baseSeconds = Math.max(MIN_BASE_SECONDS, (int) Math.round(scale * BASE_SECONDS_PER_SCALE));
        int score = score((double) gameTicks / TICKS_PER_SECOND, baseSeconds);

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.changeGameMode(GameMode.SPECTATOR);
        }
        HudManager.showTitle(server, "§6§l掃討完了！ スコア " + score,
            "§fクリアタイム " + time + " §7(基準 " + formatTime(baseSeconds) + "、規模 " + scale + ")");
        server.getPlayerManager().broadcast(
            Text.literal("§6§l[Mopup] 掃討完了！ スコア: " + score + " （クリアタイム: " + time
                + "、基準: " + formatTime(baseSeconds) + "、規模: " + scale + "）"), false);
        broadcastSeed(server);
    }

    private static int score(double clearSeconds, int baseSeconds) {
        if (baseSeconds <= 0) return 0;
        double r = clearSeconds / baseSeconds;
        return (int) Math.round(100 / (1 + r * r));
    }

    private static String formatTime(int seconds) {
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    // ========== GAME OVER ==========

    private static void gameOver(MinecraftServer server, String reason) {
        currentPhase = Phase.LOST;
        HudManager.hide();

        String time = formatTime(gameTicks / TICKS_PER_SECOND);

        // Dead player also respawns in spectator
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.changeGameMode(GameMode.SPECTATOR);
        }
        HudManager.showTitle(server, "§c§lゲームオーバー", "§f経過時間 " + time + " §7(規模 " + scale + ")");
        server.getPlayerManager().broadcast(
            Text.literal("§c§l[Mopup] " + reason + " ゲームオーバー（経過時間: " + time + "、規模: " + scale + "）"), false);
        broadcastSeed(server);
    }

    // Shown on clear / game over so that a screenshot of the chat identifies the world
    private static void broadcastSeed(MinecraftServer server) {
        if (gameWorld == null) return;
        String seed = Long.toString(gameWorld.getSeed());
        // Same click-to-copy behavior as vanilla /seed
        Text seedText = Text.literal(seed).styled(style -> style
            .withColor(Formatting.GREEN)
            .withClickEvent(new ClickEvent.CopyToClipboard(seed))
            .withHoverEvent(new HoverEvent.ShowText(Text.translatable("chat.copy.click"))));
        server.getPlayerManager().broadcast(
            Text.literal("§7[Mopup] seed: ").append(seedText), false);
    }

    // ========== TICK EVENT ==========

    public static void resetState() {
        HudManager.hide();
        currentPhase = Phase.IDLE;
        gameWorld = null;
        gameTicks = 0;
        clearTicksRemaining = -1;
        targetSeen = false;
    }

    public static void registerEvents() {
        ServerTickEvents.END_SERVER_TICK.register(GameManager::onTick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> resetState());

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (currentPhase == Phase.GAME && entity instanceof ServerPlayerEntity player) {
                gameOver(player.getEntityWorld().getServer(), player.getName().getString() + " が死亡！");
            }
        });
    }

    private static void onTick(MinecraftServer server) {
        if (currentPhase != Phase.GAME) return;

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!player.isAlive()) continue;
            int y = player.getBlockPos().getY();
            if (y < Y_GAME_OVER) {
                gameOver(server, player.getName().getString() + " が y<" + Y_GAME_OVER + " に潜った！");
                return;
            }
            if (y > ceilingY) {
                gameOver(server, player.getName().getString() + " が y>" + ceilingY + " に登った！");
                return;
            }
        }

        // Counted every tick so the display and countdown stay real-time
        List<Entity> targets = findTargets();
        int targetCount = targets.size();

        // Glow window: first 10 seconds of every minute, starting from 1:00 (no glow right after start).
        // Re-applied every second so that newly spawned mobs also glow.
        int cyclePos = gameTicks % GLOW_CYCLE_TICKS;
        boolean glowing = gameTicks >= GLOW_CYCLE_TICKS && cyclePos < GLOW_DURATION_TICKS;
        if (glowing && cyclePos % TICKS_PER_SECOND == 0) {
            int remaining = GLOW_DURATION_TICKS - cyclePos;
            for (Entity e : targets) {
                if (e instanceof MobEntity mob) {
                    mob.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, remaining, 0, false, false));
                }
            }
        }

        // Clear countdown
        if (targetCount > 0 && !targetSeen) {
            targetSeen = true;
        }
        if (targetCount == 0 && targetSeen) {
            if (clearTicksRemaining < 0) {
                clearTicksRemaining = CLEAR_TICKS;
                server.getPlayerManager().broadcast(
                    Text.literal("§a[Mopup] 地上の敵モブが 0 になりました！ " + CLEAR_SECONDS + " 秒維持すれば勝利！"), false);
            }
            clearTicksRemaining--;
        } else if (clearTicksRemaining >= 0) {
            clearTicksRemaining = -1;
            server.getPlayerManager().broadcast(
                Text.literal("§c[Mopup] 敵モブ出現！ カウントダウンをリセットしました。"), false);
        }

        int glowSecondsLeft = glowing
            ? ceilSeconds(GLOW_DURATION_TICKS - cyclePos)
            : ceilSeconds(GLOW_CYCLE_TICKS - cyclePos);
        HudManager.update(server, targetCount, targetSeen, clearTicksRemaining, CLEAR_TICKS, glowing, glowSecondsLeft, scale,
            Y_GAME_OVER, ceilingY);

        gameTicks++;

        if (clearTicksRemaining == 0) {
            win(server);
        }
    }

    private static int ceilSeconds(int ticks) {
        return (ticks + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND;
    }
}
