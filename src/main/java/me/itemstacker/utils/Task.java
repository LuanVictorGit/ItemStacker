package me.itemstacker.utils;

import org.bukkit.Bukkit;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import me.itemstacker.Core;

@Getter
@AllArgsConstructor
public class Task {

    @Setter private Runnable runnable;

    public void run() {
        if (this.runnable == null) return;
        this.runnable.run();
    }

    public static void run(Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            Bukkit.getScheduler().runTask(Core.getInstance(), runnable);
        }
    }

    public static void runAsync(Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTaskAsynchronously(Core.getInstance(), runnable);
        } else {
            runnable.run();
        }
    }
    
}