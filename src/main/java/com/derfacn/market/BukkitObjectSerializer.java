package com.derfacn.market;

import org.bukkit.inventory.ItemStack;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class BukkitObjectSerializer {

    public static byte[] itemStackToBytes(ItemStack item) throws IOException {
        YamlConfiguration config = new YamlConfiguration();
        config.set("item", item);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        outputStream.write(config.saveToString().getBytes());
        return outputStream.toByteArray();
    }

    public static ItemStack bytesToItemStack(byte[] bytes) throws IOException {
        String data = new String(bytes);
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(data);
        } catch (Exception e) {
            throw new IOException("Не вдалося десеріалізувати ItemStack", e);
        }
        return config.getItemStack("item");
    }
}
