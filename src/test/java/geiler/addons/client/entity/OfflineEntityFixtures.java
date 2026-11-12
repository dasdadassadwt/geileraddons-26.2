package geiler.addons.client.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import sun.misc.Unsafe;

import java.lang.reflect.Field;

/** Minimal entity fixtures for checks that run without a booted Minecraft world. */
public final class OfflineEntityFixtures {
	private OfflineEntityFixtures() { }

	public static ArmorStand armorStand(double x, double y, double z,
		double width, double height, double depth) {
		ArmorStand stand = allocateArmorStand();
		Vec3 position = new Vec3(x, y, z);
		setPosition(stand, position);
		stand.xo = x;
		stand.yo = y;
		stand.zo = z;
		stand.setBoundingBox(AABB.ofSize(position, width, height, depth));
		return stand;
	}

	/** Updates the current position while retaining the previous position for partial-tick interpolation. */
	public static void moveCurrent(ArmorStand stand, double x, double y, double z) {
		setPosition(stand, new Vec3(x, y, z));
	}

	private static ArmorStand allocateArmorStand() {
		try {
			Field field = Unsafe.class.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			return (ArmorStand) ((Unsafe) field.get(null)).allocateInstance(ArmorStand.class);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("could not create an offline armor-stand fixture", e);
		}
	}

	private static void setPosition(Entity entity, Vec3 position) {
		try {
			Field field = Entity.class.getDeclaredField("position");
			field.setAccessible(true);
			field.set(entity, position);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("could not initialize an offline entity position", e);
		}
	}
}
