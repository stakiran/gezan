package com.stakiran.mopup;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// lightgame100 のキットからエリトラとロケット花火を除き、松明を加えて種類ごとに並べ直したもの
public class KitManager {

    public static void giveKit(ServerPlayerEntity player) {
        Registry<Enchantment> enchantmentRegistry = player.getEntityWorld()
            .getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);

        List<ItemStack> contents = new ArrayList<>(Collections.nCopies(27, ItemStack.EMPTY));

        // 左4列に装備・消耗品、右3列に松明をまとめる（特定のホットバー配置を前提にしない並び）
        // Row 1: ネザライトの剣, ネザライトのツルハシ, ネザライトの斧, ネザライトのショベル, (空き), (空き), 松明 x3
        contents.set(0, new ItemStack(Items.NETHERITE_SWORD));
        contents.set(1, createPickaxe(enchantmentRegistry));
        contents.set(2, createAxe(enchantmentRegistry));
        contents.set(3, createShovel(enchantmentRegistry));

        // Row 2: 鉄ヘルメット, 鉄チェストプレート, 鉄レギンス, 鉄ブーツ, (空き), (空き), 松明 x3
        contents.set(9, new ItemStack(Items.IRON_HELMET));
        contents.set(10, new ItemStack(Items.IRON_CHESTPLATE));
        contents.set(11, new ItemStack(Items.IRON_LEGGINGS));
        contents.set(12, new ItemStack(Items.IRON_BOOTS));

        // Row 3: 丸石, 盾, 水バケツ, 牛肉, (空き), (空き), 盾, 松明 x2
        contents.set(18, new ItemStack(Items.COBBLESTONE, 64));
        contents.set(19, new ItemStack(Items.SHIELD));
        contents.set(20, new ItemStack(Items.WATER_BUCKET));
        contents.set(21, new ItemStack(Items.COOKED_BEEF, 64));
        contents.set(24, new ItemStack(Items.SHIELD));

        for (int slot : new int[] {6, 7, 8, 15, 16, 17, 25, 26}) {
            contents.set(slot, new ItemStack(Items.TORCH, 64));
        }

        ItemStack shulkerBox = new ItemStack(Items.SHULKER_BOX);
        shulkerBox.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(contents));

        // Reset inventory to the starting state (makes retrying easy)
        player.getInventory().clear();
        player.giveItemStack(shulkerBox);
    }

    private static ItemStack createPickaxe(Registry<Enchantment> registry) {
        ItemStack stack = new ItemStack(Items.NETHERITE_PICKAXE);
        ItemEnchantmentsComponent.Builder builder = new ItemEnchantmentsComponent.Builder(ItemEnchantmentsComponent.DEFAULT);
        addEnchantment(builder, registry, Enchantments.EFFICIENCY, 5);
        addEnchantment(builder, registry, Enchantments.AQUA_AFFINITY, 1);
        addEnchantment(builder, registry, Enchantments.UNBREAKING, 3);
        addEnchantment(builder, registry, Enchantments.MENDING, 1);
        stack.set(DataComponentTypes.ENCHANTMENTS, builder.build());
        return stack;
    }

    private static ItemStack createAxe(Registry<Enchantment> registry) {
        ItemStack stack = new ItemStack(Items.NETHERITE_AXE);
        ItemEnchantmentsComponent.Builder builder = new ItemEnchantmentsComponent.Builder(ItemEnchantmentsComponent.DEFAULT);
        addEnchantment(builder, registry, Enchantments.EFFICIENCY, 5);
        addEnchantment(builder, registry, Enchantments.UNBREAKING, 3);
        addEnchantment(builder, registry, Enchantments.MENDING, 1);
        stack.set(DataComponentTypes.ENCHANTMENTS, builder.build());
        return stack;
    }

    private static ItemStack createShovel(Registry<Enchantment> registry) {
        ItemStack stack = new ItemStack(Items.NETHERITE_SHOVEL);
        ItemEnchantmentsComponent.Builder builder = new ItemEnchantmentsComponent.Builder(ItemEnchantmentsComponent.DEFAULT);
        addEnchantment(builder, registry, Enchantments.EFFICIENCY, 5);
        addEnchantment(builder, registry, Enchantments.UNBREAKING, 3);
        addEnchantment(builder, registry, Enchantments.MENDING, 1);
        stack.set(DataComponentTypes.ENCHANTMENTS, builder.build());
        return stack;
    }

    private static void addEnchantment(ItemEnchantmentsComponent.Builder builder,
            Registry<Enchantment> registry, RegistryKey<Enchantment> key, int level) {
        registry.getEntry(key.getValue()).ifPresent(entry -> builder.add(entry, level));
    }
}
