package com.stakiran.mopup;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.MobEntity;

import java.util.Set;

// ターゲットモブの判定
public class TargetMobs {

    private static final Set<EntityType<?>> TYPES = Set.of(
        // ゾンビと亜種（チビゾンビは ZOMBIE に含まれる）
        EntityType.ZOMBIE,
        EntityType.ZOMBIE_VILLAGER,
        EntityType.ZOMBIE_HORSE,
        EntityType.HUSK,
        EntityType.CAMEL_HUSK,
        EntityType.CREEPER,
        EntityType.SKELETON,
        EntityType.SPIDER,
        EntityType.ENDERMAN,
        EntityType.WITCH,
        EntityType.BOGGED,
        EntityType.STRAY,
        EntityType.PARCHED,
        EntityType.SKELETON_HORSE
    );

    public static boolean isTarget(Entity entity) {
        if (!entity.isAlive() || !(entity instanceof MobEntity)) return false;
        if (TYPES.contains(entity.getType())) return true;

        // チキンジョッキー: ターゲットモブを乗せているニワトリ
        if (entity.getType() == EntityType.CHICKEN) {
            for (Entity passenger : entity.getPassengerList()) {
                if (TYPES.contains(passenger.getType())) return true;
            }
        }
        return false;
    }
}
