package com.stakiran.gezan;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.BedPart;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.GameMode;
import net.minecraft.world.Heightmap;
import net.minecraft.world.WorldProperties;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.rule.GameRules;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class GameManager {

    public enum Phase {
        IDLE,   // No game running
        GAME,   // Descending
        WON     // Reached the ground
    }

    private static Phase currentPhase = Phase.IDLE;
    private static ServerWorld gameWorld;

    private static final int TICKS_PER_SECOND = 20;

    // Feet Y at or below this is "ground"
    private static final int GROUND_Y = 64;
    // The highest point must be more than this above the ground, otherwise the location is retried
    private static final int MIN_DROP = 128;
    // Retry: teleport to random X/Z in [-RETRY_RANGE, RETRY_RANGE]
    private static final int RETRY_RANGE = 1000;
    private static final int MAX_RETRIES = 20;

    // Win condition: stay on the ground for 5 seconds without leaving
    private static final int WIN_SECONDS = 5;
    private static final int WIN_TICKS = WIN_SECONDS * TICKS_PER_SECOND;
    // Consecutive ticks each player has spent on the ground (0 = not on the ground)
    private static final Map<UUID, Integer> groundTicks = new HashMap<>();

    private static final int BORDER_SIZE = 100;
    private static final long MIDNIGHT = 18000;

    private static int gameTicks;
    // Game scale measured at setup (see TerrainScanner.Result.scale)
    private static int scale;
    // Feet Y of the start position (on top of the bed)
    private static int startY;

    // The bed placed at the highest point (respawn point). Breaking it is a death.
    private static BlockPos bedFootPos;
    private static BlockPos bedHeadPos;
    private static Direction bedFacing;

    // ========== SETUP ==========

    // Can be called at any time (also works as a reset)
    public static void setup(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return;

        MinecraftServer server = source.getServer();
        gameWorld = player.getEntityWorld();
        currentPhase = Phase.IDLE;
        HudManager.hide(); // Bars are (re)created on the next tick

        // 1: Amplified check (warning only)
        if (!isAmplified(gameWorld)) {
            server.getPlayerManager().broadcast(
                Text.literal("§c[Gezan] 警告: このワールドはアンプリファイドではありません。高低差が足りずやり直しが続く可能性があります。"), false);
        }

        // 2: Creative while setting up (safe to teleport around)
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            p.changeGameMode(GameMode.CREATIVE);
        }

        // 2-4: Border around the player, find the highest point, retry elsewhere if the drop is too small
        double centerX = player.getX();
        double centerZ = player.getZ();
        TerrainScanner.Result scan;
        int attempt = 0;
        while (true) {
            placeBorder(centerX, centerZ);
            scan = TerrainScanner.scan(gameWorld, GROUND_Y);
            int drop = scan.highest() == null ? -1 : scan.highest().getY() - GROUND_Y;
            if (drop > MIN_DROP) break;

            attempt++;
            if (attempt > MAX_RETRIES) {
                server.getPlayerManager().broadcast(
                    Text.literal("§c[Gezan] " + MAX_RETRIES + " 回やり直しても高低差 " + MIN_DROP + " 超の地点が見つかりませんでした。現在地で開始します。"), false);
                break;
            }
            centerX = gameWorld.getRandom().nextBetween(-RETRY_RANGE, RETRY_RANGE) + 0.5;
            centerZ = gameWorld.getRandom().nextBetween(-RETRY_RANGE, RETRY_RANGE) + 0.5;
            server.getPlayerManager().broadcast(
                Text.literal(String.format("§e[Gezan] 高低差 %d が %d 以下のためやり直し (%d 回目): x=%d z=%d へ移動",
                    drop, MIN_DROP, attempt, (int) Math.floor(centerX), (int) Math.floor(centerZ))), false);
            int y = gameWorld.getTopY(Heightmap.Type.WORLD_SURFACE, (int) Math.floor(centerX), (int) Math.floor(centerZ));
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                p.teleport(gameWorld, centerX, y + 1, centerZ, Set.of(), p.getYaw(), p.getPitch(), false);
            }
        }
        if (scan.highest() == null) {
            server.getPlayerManager().broadcast(Text.literal("§c[Gezan] 最高点が見つかりませんでした。"), false);
            resetState();
            return;
        }
        scale = scan.scale();

        // 5: Bed at the highest point = respawn point; move players there
        placeBed(scan.highest());
        startY = bedFootPos.getY() + 1;

        // 6: Night, no time advance, keep inventory
        //   /gamerule advance_time false, /time set midnight, /gamerule keep_inventory true
        // Done via API because commands dispatched from inside another command are queued and never run.
        gameWorld.getGameRules().setValue(GameRules.ADVANCE_TIME, false, server);
        gameWorld.getGameRules().setValue(GameRules.KEEP_INVENTORY, true, server);
        for (ServerWorld world : server.getWorlds()) {
            world.setTimeOfDay(MIDNIGHT);
        }

        // 5, 7, 9: Reset player state so that a retry starts fair
        float yaw = bedFacing.getOpposite().getPositiveHorizontalDegrees();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            p.setSpawnPoint(new ServerPlayerEntity.Respawn(
                WorldProperties.SpawnPoint.create(gameWorld.getRegistryKey(), bedFootPos, yaw, 0.0f), true), false);
            p.teleport(gameWorld, bedFootPos.getX() + 0.5, startY, bedFootPos.getZ() + 0.5, Set.of(), yaw, 0.0f, false);
            KitManager.giveKit(p);
            p.setHealth(p.getMaxHealth());
            p.getHungerManager().setFoodLevel(20);
            p.getHungerManager().setSaturationLevel(5.0f);
            p.clearStatusEffects();
            p.extinguish();
            p.setExperienceLevel(0);
            p.setExperiencePoints(0);
            p.changeGameMode(GameMode.SURVIVAL);
        }

        currentPhase = Phase.GAME;
        gameTicks = 0;
        groundTicks.clear();

        server.getPlayerManager().broadcast(
            Text.literal("§a§l[Gezan] ゲーム開始！ 地上 (y<=" + GROUND_Y + ") に到達して " + WIN_SECONDS + " 秒とどまれ！"), false);
        server.getPlayerManager().broadcast(
            Text.literal(String.format("§e[Gezan] 規模: %d （屋外 %,d マス / 屋内・洞窟 %,d マス） 高低差: %d",
                scale, scan.outdoor(), scan.indoor(), startY - GROUND_Y)), false);
        server.getPlayerManager().broadcast(
            Text.literal("§c[Gezan] 死亡条件: 水に触れる / 原木・板材を入手する / 開始地点のベッドを壊す"), false);
        // 8: Seed and start position, both click-to-copy
        broadcastSeedAndPos(server);
    }

    private static boolean isAmplified(ServerWorld world) {
        ChunkGenerator generator = world.getChunkManager().getChunkGenerator();
        return generator instanceof NoiseChunkGenerator noise && noise.matchesSettings(ChunkGeneratorSettings.AMPLIFIED);
    }

    //   /worldborder center <x> <z>
    //   /worldborder set 100
    private static void placeBorder(double centerX, double centerZ) {
        WorldBorder border = gameWorld.getWorldBorder();
        border.setCenter(centerX, centerZ);
        border.setSize(BORDER_SIZE);
    }

    // Place a bed on top of the highest block. The head goes toward a neighbor at the same height
    // if there is one; otherwise cobblestone is put under the head so the bed does not float.
    private static void placeBed(BlockPos highest) {
        bedFacing = Direction.NORTH;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos neighbor = highest.offset(dir);
            if (!gameWorld.getBlockState(neighbor).getCollisionShape(gameWorld, neighbor).isEmpty()) {
                bedFacing = dir;
                break;
            }
        }
        bedFootPos = highest.up();
        bedHeadPos = bedFootPos.offset(bedFacing);

        BlockPos underHead = bedHeadPos.down();
        if (gameWorld.getBlockState(underHead).getCollisionShape(gameWorld, underHead).isEmpty()) {
            gameWorld.setBlockState(underHead, Blocks.COBBLESTONE.getDefaultState());
        }
        // Head room above the bed (the highest point may be a log under leaves)
        for (BlockPos base : new BlockPos[] {bedFootPos, bedHeadPos}) {
            for (int dy = 1; dy <= 2; dy++) {
                gameWorld.setBlockState(base.up(dy), Blocks.AIR.getDefaultState());
            }
        }
        setBedBlocks();
    }

    private static void setBedBlocks() {
        BlockState foot = Blocks.RED_BED.getDefaultState()
            .with(BedBlock.FACING, bedFacing).with(BedBlock.PART, BedPart.FOOT);
        BlockState head = Blocks.RED_BED.getDefaultState()
            .with(BedBlock.FACING, bedFacing).with(BedBlock.PART, BedPart.HEAD);
        gameWorld.setBlockState(bedFootPos, foot);
        gameWorld.setBlockState(bedHeadPos, head);
    }

    private static boolean isBedIntact() {
        return gameWorld.getBlockState(bedFootPos).isIn(BlockTags.BEDS)
            && gameWorld.getBlockState(bedHeadPos).isIn(BlockTags.BEDS);
    }

    // ========== WIN ==========

    private static void win(MinecraftServer server, ServerPlayerEntity winner) {
        currentPhase = Phase.WON;
        HudManager.hide();

        String time = HudManager.formatTime(gameTicks / TICKS_PER_SECOND);

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.changeGameMode(GameMode.SPECTATOR);
        }
        HudManager.showTitle(server, "§6§l下山完了！ " + time,
            "§7規模 " + scale + "、高低差 " + (startY - GROUND_Y));
        server.getPlayerManager().broadcast(
            Text.literal("§6§l[Gezan] " + winner.getName().getString() + " が下山完了！ タイム: " + time
                + " （規模: " + scale + "、高低差: " + (startY - GROUND_Y) + "）"), false);
        broadcastSeedAndPos(server);
    }

    // Shown on start / clear so that a screenshot of the chat identifies the world and the course
    private static void broadcastSeedAndPos(MinecraftServer server) {
        if (gameWorld == null || bedFootPos == null) return;
        String seed = Long.toString(gameWorld.getSeed());
        String pos = bedFootPos.getX() + " " + startY + " " + bedFootPos.getZ();
        server.getPlayerManager().broadcast(
            Text.literal("§7[Gezan] seed: ").append(copyable(seed))
                .append(Text.literal(" §7/ 開始地点 (x y z): ")).append(copyable(pos)), false);
    }

    // Same click-to-copy behavior as vanilla /seed
    private static Text copyable(String value) {
        return Text.literal(value).styled(style -> style
            .withColor(Formatting.GREEN)
            .withClickEvent(new ClickEvent.CopyToClipboard(value))
            .withHoverEvent(new HoverEvent.ShowText(Text.translatable("chat.copy.click"))));
    }

    // ========== DEATH ==========

    private static void kill(MinecraftServer server, ServerPlayerEntity player, String reason) {
        server.getPlayerManager().broadcast(
            Text.literal("§c[Gezan] " + player.getName().getString() + " が" + reason + "！ 死亡"), false);
        groundTicks.put(player.getUuid(), 0);
        player.kill(gameWorld);
    }

    // Removes logs / planks from the inventory (including the cursor). Returns true if any were found.
    // Removed so that the player does not die again right after respawning with keep inventory on.
    private static boolean removeForbiddenItems(ServerPlayerEntity player) {
        boolean found = false;
        PlayerInventory inventory = player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            if (isForbidden(inventory.getStack(i))) {
                inventory.setStack(i, ItemStack.EMPTY);
                found = true;
            }
        }
        if (isForbidden(player.currentScreenHandler.getCursorStack())) {
            player.currentScreenHandler.setCursorStack(ItemStack.EMPTY);
            found = true;
        }
        return found;
    }

    private static boolean isForbidden(ItemStack stack) {
        return !stack.isEmpty() && (stack.isIn(ItemTags.LOGS) || stack.isIn(ItemTags.PLANKS));
    }

    // ========== TICK EVENT ==========

    public static void resetState() {
        HudManager.hide();
        currentPhase = Phase.IDLE;
        gameWorld = null;
        gameTicks = 0;
        groundTicks.clear();
        bedFootPos = null;
        bedHeadPos = null;
    }

    public static void registerEvents() {
        ServerTickEvents.END_SERVER_TICK.register(GameManager::onTick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> resetState());
    }

    private static void onTick(MinecraftServer server) {
        if (currentPhase != Phase.GAME) return;

        // Bed broken: everyone dies, and the bed is restored so that it keeps working as the respawn point
        if (!isBedIntact()) {
            setBedBlocks();
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (player.isAlive()) {
                    kill(server, player, "開始地点のベッドを壊した");
                }
            }
        }

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            UUID uuid = player.getUuid();
            if (!player.isAlive()) {
                groundTicks.put(uuid, 0);
                continue;
            }
            if (player.isTouchingWater()) {
                kill(server, player, "水に触れた");
                continue;
            }
            if (removeForbiddenItems(player)) {
                kill(server, player, "原木・板材を入手した");
                continue;
            }

            int y = player.getBlockPos().getY();
            int onGround = groundTicks.getOrDefault(uuid, 0);
            if (y <= GROUND_Y) {
                if (onGround == 0) {
                    server.getPlayerManager().broadcast(
                        Text.literal("§a[Gezan] " + player.getName().getString() + " が地上に到達！ " + WIN_SECONDS + " 秒とどまれば勝利！"), false);
                }
                onGround++;
                groundTicks.put(uuid, onGround);
                if (onGround >= WIN_TICKS) {
                    win(server, player);
                    return;
                }
            } else if (onGround > 0) {
                groundTicks.put(uuid, 0);
                server.getPlayerManager().broadcast(
                    Text.literal("§c[Gezan] " + player.getName().getString() + " が地上から離れた！ カウントをリセットしました。"), false);
            }
        }

        HudManager.update(server, gameTicks, startY, GROUND_Y, groundTicks, WIN_TICKS, scale);
        gameTicks++;
    }
}
