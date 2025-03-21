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
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * RemoteBoxPlugin
 *
 * 要点:
 *  - "使用できないアイテム"を登録しようとした場合はエラーメッセージを出して登録しない
 *  - シュルカーボックスから優先的に消費、それが無い場合はインベントリから消費
 *  - メインハンドのアイテムは減らさない(何度でも置けるように見える)
 *  - debugモードがfalseのとき、内部メッセージは表示しない
 *  - data.ymlに登録情報を保存、config.ymlはメッセージや設定のみ
 */
public final class RemoteBoxPlugin extends JavaPlugin implements Listener {

    // プレイヤーごとの登録アイテム(1種)
    private final Map<UUID, ItemStack> registeredBlocks = new HashMap<>();

    // data.yml
    private File dataFile;
    private YamlConfiguration dataConfig;

    // config.yml から読み込む設定/メッセージ
    private String prefix;
    private String msgRegistered;
    private String msgUnregistered;
    private String msgNotFound;
    private String msgPlaced;
    private String msgNoItemInInv;
    private String msgNotUsable;

    private int maxRegisteredBlocks;
    private boolean debug;  // debugモードフラグ

    @Override
    public void onEnable() {
        // config.yml 初回展開
        saveDefaultConfig();
        loadPluginConfig();

        // data.yml 準備
        setupDataFile();
        loadDataFile();

        // イベント登録
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        // 停止時にデータ保存
        saveDataFile();
    }

    /**
     * config.yml からメッセージ/設定を読み込む
     */
    private void loadPluginConfig() {
        FileConfiguration config = getConfig();
        this.prefix = config.getString("messages.prefix", "§7[RemoteBox] ");
        this.msgRegistered = config.getString("messages.registered", "§aブロックを登録しました。");
        this.msgUnregistered = config.getString("messages.unregistered", "§a登録を解除しました。");
        this.msgNotFound = config.getString("messages.notFound", "§c登録されたブロックがありません。");
        this.msgPlaced = config.getString("messages.placed", "§aブロックを消費して設置しました。");
        this.msgNoItemInInv = config.getString("messages.noItemInInventory", "§cシュルカーボックスにもインベントリにも登録ブロックがありません。");
        this.msgNotUsable = config.getString("messages.notUsableItem", "§cこのアイテムは使用できません。");

        this.maxRegisteredBlocks = config.getInt("maxRegisteredBlocks", 1);
        this.debug = config.getBoolean("debug", false);  // デバッグモード
    }

    /**
     * data.yml のファイル準備
     */
    private void setupDataFile() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                getDataFolder().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("data.yml作成に失敗: " + e.getMessage());
            }
        }
        dataConfig = new YamlConfiguration();
    }

    /**
     * data.yml から読み込み
     */
    private void loadDataFile() {
        try {
            dataConfig.load(dataFile);
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("data.yml読み込み失敗: " + e.getMessage());
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
                getLogger().warning("登録データ読み込み失敗: " + key + " => " + e.getMessage());
            }
        }
    }

    /**
     * data.yml に書き込み
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
            getLogger().severe("data.yml保存失敗: " + e.getMessage());
        }
    }

    /**
     * コマンド処理
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("プレイヤーのみ使用できます。");
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
     *  - 使用できないアイテムは登録できない
     *  - ここでは "ブロックとして設置可能なもの" だけ登録OKという想定例
     */
    private void handleRegisterItem(Player player, UUID uuid) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            player.sendMessage(prefix + "登録するブロックを手に持ってください。");
            return;
        }
        // 例えば "ブロックである" ことを条件に "使用可能" とする例
        // isBlock() メソッドが無い古いバージョンの場合はMaterialカテゴリなどで判定する必要あり
        if (!hand.getType().isBlock()) {
            player.sendMessage(prefix + msgNotUsable);
            return;
        }

        // maxRegisteredBlocks = 1 の簡易実装: 既にあっても上書き
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
     * ブロック設置イベント
     *  - 登録ブロックを置こうとしたらキャンセルし、1tick後に置く
     *  - アイテムはメインハンドを減らさず、シュルカーボックス→インベントリ の順に消費
     */
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // 登録がなければ無視
        if (!registeredBlocks.containsKey(uuid)) return;

        ItemStack registeredItem = registeredBlocks.get(uuid);
        ItemStack inHand = event.getItemInHand();

        // 持ちアイテムが登録ブロックと一致しないなら無視
        if (!inHand.isSimilar(registeredItem)) return;

        // キャンセル(サーバー標準の消費を止める)
        event.setCancelled(true);

        // シュルカーボックスを最優先で消費
        boolean consumed = tryConsumeInShulkers(player, registeredItem);

        // シュルカに無ければインベントリから消費
        if (!consumed) {
            consumed = tryConsumeInventory(player, registeredItem);
        }

        // 見つからなければ置けない
        if (!consumed) {
            player.sendMessage(prefix + msgNoItemInInv);
            return;
        }

        // 消費成功 → 1tick後に設置
        final Block blockToSet = event.getBlock();
        final Material placeMaterial = registeredItem.getType();

        Bukkit.getScheduler().runTask(this, () -> {
            blockToSet.setType(placeMaterial);
            player.sendMessage(prefix + msgPlaced);
        });
    }

    /**
     * シュルカーボックスから最優先で消費
     *  debugモードがtrueの時のみ "シュルカーボックスから消費しました" 的なログを出す
     */
    private boolean tryConsumeInShulkers(Player player, ItemStack target) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack box = inv.getItem(i);
            if (box == null) continue;
            if (Tag.SHULKER_BOXES.isTagged(box.getType())) {
                if (!(box.getItemMeta() instanceof BlockStateMeta)) continue;
                BlockStateMeta meta = (BlockStateMeta) box.getItemMeta();
                if (!(meta.getBlockState() instanceof ShulkerBox)) continue;

                ShulkerBox shulker = (ShulkerBox) meta.getBlockState();
                Inventory shulkerInv = shulker.getInventory();

                for (int slot = 0; slot < shulkerInv.getSize(); slot++) {
                    ItemStack content = shulkerInv.getItem(slot);
                    if (content != null && content.isSimilar(target)) {
                        consumeItem(shulkerInv, slot);

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
     * インベントリ(手持ち以外)から消費
     *  debugモードがtrueの時のみ内部メッセージを出す
     */
    private boolean tryConsumeInventory(Player player, ItemStack target) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack slotItem = inv.getItem(i);
            if (slotItem == null) continue;

            // メインハンドをスキップ(手持ちは減らさない)
            if (i == player.getInventory().getHeldItemSlot()) {
                continue;
            }

            if (slotItem.isSimilar(target)) {
                consumeItem(inv, i);
                if (debug) {
                    player.sendMessage(prefix + "§7(インベントリから使用されました)");
                }
                return true;
            }
        }
        return false;
    }

    /**
     * スロットのアイテムを1個消費
     */
    private void consumeItem(Inventory inv, int slot) {
        ItemStack item = inv.getItem(slot);
        if (item == null) return;

        int amount = item.getAmount();
        if (amount > 1) {
            item.setAmount(amount - 1);
        } else {
            inv.setItem(slot, null);
        }
    }
}
