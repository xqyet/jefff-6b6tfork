package com.stash.hunt.modules;

import com.stash.hunt.Addon;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.Vec3d;
import net.minecraft.client.MinecraftClient;


public class ChunkPacer extends Module {
    private final MinecraftClient mc = MinecraftClient.getInstance();

    private final Setting<Integer> walkTime = settings.getDefaultGroup().add(new IntSetting.Builder()
        .name("walk-time")
        .description("Milliseconds to walk forward.")
        .defaultValue(1000)
        .min(100)
        .sliderMax(10000)
        .build()
    );

    private final Setting<Integer> stopTime = settings.getDefaultGroup().add(new IntSetting.Builder()
        .name("stop-time")
        .description("Milliseconds to stop walking.")
        .defaultValue(1000)
        .min(100)
        .sliderMax(10000)
        .build()
    );


    private boolean walking = true;
    private Thread loopThread;
    private volatile boolean running = false;

    public ChunkPacer() {
        super(Addon.CATEGORY, "ChunkPacer6b6t", "Chunk Pacing for 6b6t trailfollower (all dimensions)");
    }

    private float lastYaw = 0;
    private float lastPitch = 0;

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!shouldWalk() || mc.player == null || mc.world == null) return;

        boolean isWearingElytra = mc.player.getInventory().armor.get(2).getItem().getTranslationKey().contains("elytra");
        boolean isAirborne = !mc.player.isOnGround();
        boolean isFlyingWithElytra = isWearingElytra && isAirborne;

        // Only capture new direction if not paused
        if (!mc.isPaused()) {
            lastYaw = mc.player.getYaw();
            lastPitch = mc.player.getPitch();
        }

        if (isFlyingWithElytra) {
            double speed = 1.0;
            double rad = Math.toRadians(lastYaw);
            double x = -Math.sin(rad) * speed;
            double z = Math.cos(rad) * speed;

            mc.player.setVelocity(new Vec3d(x, mc.player.getVelocity().y, z));
        } else {
            // Regular ground walking
            double speed = 0.2;
            double rad = Math.toRadians(mc.player.getYaw());
            double x = -Math.sin(rad) * speed;
            double z = Math.cos(rad) * speed;

            mc.player.setVelocity(new Vec3d(x, mc.player.getVelocity().y, z));
        }
    }




    @Override
    public void onActivate() {
        walking = true;
        running = true;

        loopThread = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(walking ? walkTime.get() : stopTime.get());
                } catch (InterruptedException ignored) {}

                walking = !walking;
            }
        });

        loopThread.setDaemon(true);
        loopThread.start();
    }

    @Override
    public void onDeactivate() {
        running = false;
        walking = false;
    }

    public boolean shouldWalk() {
        return isActive() && walking;
    }
}
