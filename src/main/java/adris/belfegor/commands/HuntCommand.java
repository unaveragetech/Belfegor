package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;
import adris.belfegor.tasks.entity.KillEntityTask;
import adris.belfegor.tasks.resources.KillAndLootTask;
import adris.belfegor.util.ItemTarget;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.SkeletonEntity;
import net.minecraft.entity.mob.SpiderEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.passive.RabbitEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.item.Items;

import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

/**
 * @hunt <mob> [count]
 *
 * Hunts the nearest mob of a type for food or useful drops. Lets the AI
 * advisor and players issue a concrete "go kill something" goal, e.g.
 * @hunt cow 4 or @hunt hostile.
 */
public class HuntCommand extends Command {

    public HuntCommand() throws CommandException {
        super("hunt", "Hunt the nearest mob for food or drops. Examples: @hunt cow 4, @hunt hostile");
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        String[] args = parser.getArgUnits();
        String mob = args.length == 0 ? "cow" : args[0].trim().toLowerCase(Locale.ROOT);
        int count = 1;
        if (args.length >= 2) {
            try {
                count = Math.max(1, Integer.parseInt(args[1].trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        switch (mob) {
            case "cow", "cows" -> hunt(mod, CowEntity.class, new ItemTarget(Items.BEEF, count));
            case "pig", "pigs" -> hunt(mod, PigEntity.class, new ItemTarget(Items.PORKCHOP, count));
            case "chicken", "chickens" -> hunt(mod, ChickenEntity.class, new ItemTarget(Items.CHICKEN, count));
            case "sheep" -> hunt(mod, SheepEntity.class, new ItemTarget(Items.MUTTON, count));
            case "rabbit", "rabbits" -> hunt(mod, RabbitEntity.class, new ItemTarget(Items.RABBIT, count));
            case "zombie", "zombies" -> hunt(mod, ZombieEntity.class, new ItemTarget(Items.ROTTEN_FLESH, count));
            case "skeleton", "skeletons" -> hunt(mod, SkeletonEntity.class, new ItemTarget(Items.BONE, count));
            case "spider", "spiders" -> hunt(mod, SpiderEntity.class, new ItemTarget(Items.STRING, count));
            case "creeper", "creepers" -> hunt(mod, CreeperEntity.class, new ItemTarget(Items.GUNPOWDER, count));
            case "hostile", "hostiles", "monster", "monsters" -> huntNearestHostile(mod);
            default -> throw new CommandException("Unknown mob `" + mob
                    + "`. Try cow, pig, chicken, sheep, rabbit, zombie, skeleton, spider, creeper, or hostile.");
        }
    }

    private void hunt(Belfegor mod, Class<?> toKill, ItemTarget target) {
        mod.runUserTask(new KillAndLootTask(toKill, entity -> entity instanceof LivingEntity, target),
                this::finish);
    }

    private void huntNearestHostile(Belfegor mod) throws CommandException {
        Optional<Entity> nearest = mod.getEntityTracker().getHostiles().stream()
                .filter(Entity::isAlive)
                .min(Comparator.comparingDouble(e -> e.squaredDistanceTo(mod.getPlayer())));
        if (nearest.isEmpty()) {
            throw new CommandException("No hostile mobs nearby to hunt.");
        }
        mod.runUserTask(new KillEntityTask(nearest.get()), this::finish);
    }

    @Override
    public java.util.List<String> getExamples() {
        return java.util.List.of("@hunt cow", "@hunt cow 4", "@hunt hostile");
    }

    @Override
    public String getDetailedDescription() {
        return "Hunts the nearest matching mob and collects its food/drop. "
                + "Pass a count to keep hunting until you have that many. "
                + "@hunt hostile attacks the nearest hostile mob for defense.";
    }
}
