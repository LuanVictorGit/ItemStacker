package me.itemstacker.utils;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public class API {

    public static ItemStack getItemStack(String id) {
        if (id == null || id.trim().isEmpty()) {
            return null;
        }

        id = id.trim().toLowerCase();

        Material mat = Material.matchMaterial(id);
        if (mat != null) {
            return new ItemStack(mat);
        }

        if (id.contains(">")) {
            String[] split = id.split(">");
            if (split.length == 2) {
                String idPart = split[0];
                String dataPart = split[1];

                try {
                    int numericId = Integer.parseInt(idPart);
                    short data = Short.parseShort(dataPart);

                    ItemStack stack = fromLegacyId(numericId, data);
                    if (stack != null) {
                        return stack;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        try {
            int numericId = Integer.parseInt(id);
            ItemStack stack = fromLegacyId(numericId, (short) 0);
            if (stack != null) {
                return stack;
            }
        } catch (NumberFormatException ignored) {}

        return null;
    }
    
	public static boolean isSameTypeAndData(ItemStack a, ItemStack b) {
        if (a == null || b == null) return false;
        if (a.getType() == null || b.getType() == null) return false;
        if (a.getType() != b.getType()) return false;

        try {
            short dataA = a.getDurability();
            short dataB = b.getDurability();
            return dataA == dataB;
        } catch (NoSuchMethodError ignored) {
            return true;
        }
    }
    
    @SuppressWarnings("deprecation")
    private static ItemStack fromLegacyId(int id, short data) {
        try {
        	
        	for(Material material : Material.values()) {
        		if (material.getId() != id) continue;
        		ItemStack stack = new ItemStack(material);
                stack.setDurability(data);
                return stack;
        	}
        	
        } catch (Throwable ignored) {}
        return null;
    }
}
