package com.noasaba.remotebox;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * RemoteBoxPlugin - 最終版 (PlayerInventory 使用版)
 *
 * 主な特徴:
 * - /registeritem: 手に持ったアイテムを登録 (ブロック or 特定作物)
 * - /unregisteritem: 登録を解除
 * - 登録されたアイテムを置こうとすると:
 *    1) BlockPlaceEventをキャンセル → サーバー標準のアイテム消費を止める
 *    2) シュルカーボックス > インベントリ(手持ち以外) の順に 1個消費
 *    3) 成功したら 1tick後にブロックを強制設置 (手持ちスロットは消費しない)
 * - data.yml にユーザーデータを保存 (再起動後も保持)
 * - config.yml に debug: true のときのみ内部メッセージを表示
 * - どんな状況でも増殖バグが起こらない設計
 */
public final class RemoteBoxPlugin extends JavaPlugin implements Listener {

    // プレイヤーごとに1つの登録アイテム
    private final Map<UUID, ItemStack> registeredBlocks = new HashMap<>();

    // data.yml 用
    private File dataFile;
    private YamlConfiguration dataConfig;

    // config.yml から読み込むメッセージ/設定
    private String prefix;
    private String msgRegistered;
    private String msgUnregistered;
    private String msgNotFound;
    private String msgPlaced;
    private String msgNoItemInInv;
    private String msgNotUsable;
    private int maxRegisteredBlocks;
    private boolean debug;

    // ブロック以外でも使用可能にしたい作物など
    private static final Set<Material> ALLOWED_PLANTABLE = Set.of(
            Material.WHEAT_SEEDS,
            Material.MELON_SEEDS,
            Material.PUMPKIN_SEEDS,
            Material.BEETROOT_SEEDS,
            Material.CARROT,
            Material.POTATO,
            Material.BAMBOO
    );

    @Override
    public void onEnable() {
        // config.yml 展開
        saveDefaultConfig();
        loadPluginConfig();

        // data.yml セットアップ
        setupDataFile();
        loadDataFile();

        // イベント登録
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        // 停止時にユーザーデータ保存
        saveDataFile();
    }

    /**
     * config.yml から各種設定値を読み込み
     */
    private void loadPluginConfig() {
        FileConfiguration config = getConfig();
        prefix = config.getString("messages.prefix", "§7[RemoteBox] ");
        msgRegistered = config.getString("messages.registered", "§aブロックを登録しました。");
        msgUnregistered = config.getString("messages.unregistered", "§a登録を解除しました。");
        msgNotFound = config.getString("messages.notFound", "§c登録されたブロックがありません。");
        msgPlaced = config.getString("messages.placed", "§aブロックを消費して設置しました。");
        msgNoItemInInv = config.getString("messages.noItemInInventory", "§cシュルカーボックスにもインベントリにも登録ブロックがありません。");
        msgNotUsable = config.getString("messages.notUsableItem", "§cこのアイテムは使用できません。");

        maxRegisteredBlocks = config.getInt("maxRegisteredBlocks", 1);
        debug = config.getBoolean("debug", false);
    }

    /**
     * data.yml のセットアップ
     */
    private void setupDataFile() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                getDataFolder().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("data.yml の作成に失敗: " + e.getMessage());
            }
        }
        dataConfig = new YamlConfiguration();
    }

    /**
     * data.yml を読み込み
     */
    private void loadDataFile() {
        try {
            dataConfig.load(dataFile);
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("data.yml の読み込みに失敗: " + e.getMessage());
            return;
        }
        if (!dataConfig.isConfigurationSection("registeredItems")) return;

        for (String key : dataConfig.getConfigurationSection("registeredItems").getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                Map<String, Object> map = dataConfig.getConfigurationSection("registeredItems." + key).getValues(false);
                ItemStack stack = ItemStack.deserialize(map);
                registeredBlocks.put(uuid, stack);
            } catch (Exception e) {
                getLogger().warning("登録ブロック読み込み失敗: " + key + " => " + e.getMessage());
            }
        }
    }

    /**
     * data.yml に registeredBlocks を保存
     */
    private void saveDataFile() {
        dataConfig.set("registeredItems", null);

        for (Map.Entry<UUID, ItemStack> entry : registeredBlocks.entrySet()) {
            UUID uuid = entry.getKey();
            ItemStack stack = entry.getValue();
            dataConfig.set("registeredItems." + uuid.toString(), stack.serialize());
        }

        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("data.yml の保存に失敗: " + e.getMessage());
        }
    }

    /**
     * コマンド処理
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("このコマンドはプレイヤーのみ使用できます。");
            return true;
        }
        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();

        switch (command.getName().toLowerCase()) {
            case "registeritem":
                handleRegisterItem(player, uuid);
                return true;
            case "unregisteritem":
                handleUnregisterItem(player, uuid);
                return true;
            default:
                return false;
        }
    }

    /**
     * /registeritem
     *  - ブロック or ALLOWED_PLANTABLE に含まれるアイテムのみ登録可
     */
    private void handleRegisterItem(Player player, UUID uuid) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            player.sendMessage(prefix + "登録するアイテムを手に持ってください。");
            return;
        }
        // 使用できるアイテムかチェック
        if (!isUsableItem(hand.getType())) {
            player.sendMessage(prefix + msgNotUsable);
            return;
        }

        ItemStack toRegister = hand.clone();
        toRegister.setAmount(1);

        registeredBlocks.put(uuid, toRegister);
        saveDataFile();

        player.sendMessage(prefix + msgRegistered);
    }

    /**
     * /unregisteritem
     */
    private void handleUnregisterItem(Player player, UUID uuid) {
        if (registeredBlocks.remove(uuid) != null) {
            saveDataFile();
            player.sendMessage(prefix + msgUnregistered);
        } else {
            player.sendMessage(prefix + msgNotFound);
        }
    }

    /**
     * アイテムがブロック、または ALLOWED_PLANTABLE に含まれるか判定
     */
    private boolean isUsableItem(Material mat) {
        return mat.isBlock() || ALLOWED_PLANTABLE.contains(mat);
    }

    /**
     * ブロック設置イベント
     *   - 登録されたアイテムならイベントキャンセルし、独自に在庫を1個消費 → 次のtickで設置
     *   - メインハンドスロットは消費しない
     */
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // 登録なしの場合スルー
        if (!registeredBlocks.containsKey(uuid)) return;

        ItemStack regItem = registeredBlocks.get(uuid);
        ItemStack inHand = event.getItemInHand();

        // 登録ブロックか
        if (!inHand.isSimilar(regItem)) return;

        // 通常の置き方をキャンセル → 無限増殖防止
        event.setCancelled(true);

        // シュルカボックス優先で消費
        boolean consumed = tryConsumeInShulkers(player, regItem);

        // シュルカになければインベントリ(ただしメインハンドは除外)から消費
        if (!consumed) {
            consumed = tryConsumeInventory(player, regItem);
        }

        if (!consumed) {
            player.sendMessage(prefix + msgNoItemInInv);
            return;
        }

        // 消費成功 → 1tick後に設置
        final Block placedBlock = event.getBlock();
        final Material placeMat = regItem.getType();

        Bukkit.getScheduler().runTask(this, () -> {
            placedBlock.setType(placeMat);
            player.sendMessage(prefix + msgPlaced);
        });
    }

    /**
     * シュルカボックスから1個消費
     *  debug=true なら内部メッセージを出す
     */
    private boolean tryConsumeInShulkers(Player player, ItemStack target) {
        PlayerInventory pInv = player.getInventory();
        for (int i = 0; i < pInv.getSize(); i++) {
            ItemStack box = pInv.getItem(i);
            if (box == null) continue;

            // シュルカーボックス判定
            if (Tag.SHULKER_BOXES.isTagged(box.getType())) {
                if (!(box.getItemMeta() instanceof BlockStateMeta)) continue;
                BlockStateMeta meta = (BlockStateMeta) box.getItemMeta();
                if (!(meta.getBlockState() instanceof ShulkerBox)) continue;

                ShulkerBox shulker = (ShulkerBox) meta.getBlockState();
                Inventory shulkerInv = shulker.getInventory();

                for (int slot = 0; slot < shulkerInv.getSize(); slot++) {
                    ItemStack content = shulkerInv.getItem(slot);
                    if (content != null && content.isSimilar(target)) {
                        consumeOneItem(shulkerInv, slot);
                        shulker.update();
                        meta.setBlockState(shulker);
                        box.setItemMeta(meta);

                        if (debug) {
                            player.sendMessage(prefix + "§7(シュルカーボックスから使用されました)");
                        }
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * インベントリ(手持ちスロット以外)から1個消費
     *  debug=true なら内部メッセージを表示
     */
    private boolean tryConsumeInventory(Player player, ItemStack target) {
        PlayerInventory pInv = player.getInventory();
        int handSlot = pInv.getHeldItemSlot(); // メインハンドスロット

        for (int i = 0; i < pInv.getSize(); i++) {
            // メインハンドは消費しない
            if (i == handSlot) {
                continue;
            }
            ItemStack slotItem = pInv.getItem(i);
            if (slotItem == null) continue;

            if (slotItem.isSimilar(target)) {
                consumeOneItem(pInv, i);
                if (debug) {
                    player.sendMessage(prefix + "§7(インベントリから使用されました)");
                }
                return true;
            }
        }
        return false;
    }

    /**
     * 指定スロットのアイテムを1個消費
     */
    private void consumeOneItem(Inventory inv, int slot) {
        ItemStack item = inv.getItem(slot);
        if (item == null) return;

        int amt = item.getAmount();
        if (amt > 1) {
            item.setAmount(amt - 1);
        } else {
            inv.setItem(slot, null);
        }
    }
}
