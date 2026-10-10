package com.stakiran.gezan;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

// 初期アイテムはホットバーのみ: 松明, 丸石 x6, 松明, 牛肉
public class KitManager {

    public static void giveKit(ServerPlayerEntity player) {
        // Reset inventory to the starting state (makes retrying easy)
        player.getInventory().clear();

        player.getInventory().setStack(0, new ItemStack(Items.TORCH, 64));
        for (int slot = 1; slot <= 6; slot++) {
            player.getInventory().setStack(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        player.getInventory().setStack(7, new ItemStack(Items.TORCH, 64));
        player.getInventory().setStack(8, new ItemStack(Items.COOKED_BEEF, 64));
    }
}
