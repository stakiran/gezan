package com.stakiran.mopup;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

public class MopupCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            CommandManager.literal("mopup")
                .executes(MopupCommand::executeSetup)
        );
    }

    // ゲーム中・勝利後でも呼べる（リセットを兼ねる）
    private static int executeSetup(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        if (source.getPlayer() == null) {
            source.sendFeedback(() -> Text.literal("§c[Mopup] プレイヤーがコマンドを実行してください。"), false);
            return 0;
        }

        GameManager.setup(source);
        return 1;
    }
}
