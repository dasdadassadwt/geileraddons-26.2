package geiler.addons.client.sim;

import geiler.addons.client.location.Island;
import geiler.addons.client.module.impl.I4HelperRules;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class DeviceSimChecks {
	private DeviceSimChecks() { }

	public static void run() {
		simonRounds();
		simonSkip();
		simonLimiterAndFail();
		target();
		geometry();
		fakeBlocks();
		frames();
		arrows();
		solverAims();
		cooldown();
		guardAndGate();
	}

	private static void ticks(SimonDevice device, int count) {
		for (int i = 0; i < count; i++) device.tick();
	}

	private static void simonRounds() {
		SimonDevice device = new SimonDevice(new Random(1));
		check(device.startPress(1_000), "first start press is accepted");
		ticks(device, 4);
		check(device.getRound() == 0, "the round starts 4 ticks after the press");
		ticks(device, 1);
		int first = device.patternCell(0);
		check(device.getRound() == 1 && device.lit(first) && !device.buttonUp(first), "round 1 lights its first lamp");
		check(device.press(first) == SimonDevice.Press.NONE, "no buttons while showing");
		ticks(device, 8);
		check(!device.lit(first), "a lamp stays lit for 8 ticks");
		ticks(device, 9);
		check(!device.buttonUp(first), "buttons are still gone");
		ticks(device, 1);
		check(device.buttonUp(first) && device.getAccepting(), "buttons return 18 ticks after a one lamp show");
		for (int round = 1; round <= 4; round++) {
			check(device.getRound() == round, "round " + round + " was " + device.getRound());
			for (int i = 0; i < round; i++) {
				SimonDevice.Press expected = i + 1 < round ? SimonDevice.Press.RIGHT
					: round < 4 ? SimonDevice.Press.ROUND : SimonDevice.Press.DONE;
				check(device.press(device.patternCell(i)) == expected, "press " + i + " of round " + round);
				ticks(device, 3);
			}
			ticks(device, 60);
		}
		check(device.getDone(), "four rounds finish the device");
		check(!device.startPress(900_000), "a finished device ignores the start button");
	}

	/** Start, reset, start in consecutive ticks leaves two rounds queued, so round 2 is what you put in. */
	private static void simonSkip() {
		SimonDevice device = new SimonDevice(new Random(2));
		check(device.startPress(0), "start");
		ticks(device, 1);
		check(device.startPress(100), "reset");
		ticks(device, 1);
		check(device.startPress(200), "start again");
		ticks(device, 60);
		check(device.getRound() == 2 && device.getAccepting(), "the skip opens on round 2, was " + device.getRound());
	}

	private static void simonLimiterAndFail() {
		SimonDevice device = new SimonDevice(new Random(3));
		check(device.startPress(1_000), "accepted");
		check(!device.startPress(1_060), "a second press in the same tick is dropped");
		ticks(device, 1);
		check(!device.startPress(1_048), "a press under 49 ms after the last one, one tick later, is dropped");
		check(device.startPress(1_049), "49 ms is enough");

		SimonDevice fail = new SimonDevice(new Random(4));
		fail.startPress(0);
		ticks(fail, 40);
		int wrong = (fail.patternCell(0) + 1) % 16;
		check(fail.press(wrong) == SimonDevice.Press.WRONG, "wrong button");
		check(fail.buttonPressed(wrong), "the button stays down");
		ticks(fail, 4);
		for (int cell = 0; cell < 16; cell++) check(!fail.buttonUp(cell), "buttons vanish after a fail");
		ticks(fail, 70);
		check(fail.getRound() == 2 && fail.getAccepting(), "a fail shows a new two lamp round, was " + fail.getRound());
	}

	private static void target() {
		TargetDevice device = new TargetDevice(new Random(5));
		for (int i = 0; i < 30; i++) device.tick(false);
		check(device.getLit() == -1, "nothing lights off the plate");
		check(!device.hit(device.current()), "the hidden first target does not count");
		int waited = 0;
		while (device.getLit() < 0 && waited < 11) {
			device.tick(true);
			waited++;
		}
		check(device.getLit() == device.current() && waited <= 10, "lights within 10 ticks");
		int first = device.getLit();
		check(!device.hit((first + 1) % 9), "wrong block");
		check(device.hit(first) && device.getLit() == -1 && device.getHits() == 1, "a lit hit counts and turns blue");
		check(device.hit(device.current()) && device.getHits() == 2, "the next target counts before it shows");
		for (int i = 0; i < 30; i++) device.tick(false);
		check(device.getHits() == 0 && device.getLit() == -1, "leaving the plate resets the run");

		while (device.getLit() < 0) device.tick(true);
		for (int i = 0; i < 9; i++) check(device.hit(device.current()), "hit " + i);
		check(device.getDone() && device.getHits() == 9 && !device.hit(0), "nine hits finish");
	}

	private static void geometry() {
		BlockPos plate = new BlockPos(63, 127, 35);
		// Facing south from the real plate the wall is the Floor 7 one: x 64 to 68, y 126 to 130, z 50.
		check(DeviceSimGeometry.targetPos(plate, Direction.SOUTH, 0).equals(new BlockPos(64, 126, 50)), "first target");
		check(DeviceSimGeometry.targetPos(plate, Direction.SOUTH, 8).equals(new BlockPos(68, 130, 50)), "last target");
		BlockPos stand = new BlockPos(108, 120, 93);
		check(DeviceSimGeometry.ssButton(stand, Direction.EAST, 0).equals(new BlockPos(110, 123, 92)), "top left button");
		check(DeviceSimGeometry.ssButton(stand, Direction.EAST, 15).equals(new BlockPos(110, 120, 95)), "bottom right button");
		check(DeviceSimGeometry.ssLamp(stand, Direction.EAST, 0).equals(new BlockPos(111, 123, 92)), "lamp behind");
		check(DeviceSimGeometry.ssStart(stand, Direction.EAST).equals(new BlockPos(110, 121, 91)), "start button");
		for (Direction facing : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
			check(DeviceSimGeometry.ssReserved(BlockPos.ZERO, facing).stream().distinct().count() == 34, "SS blocks " + facing);
			check(DeviceSimGeometry.targetReserved(BlockPos.ZERO, facing).stream().distinct().count() == 10, "i4 blocks " + facing);
		}
	}

	private static void fakeBlocks() {
		Map<BlockPos, BlockState> world = new HashMap<>();
		FakeBlocks.World access = new FakeBlocks.World() {
			@Override
			public BlockState get(BlockPos pos) {
				return world.getOrDefault(pos, Blocks.AIR.defaultBlockState());
			}

			@Override
			public void set(BlockPos pos, BlockState state) {
				world.put(pos, state);
			}
		};
		BlockPos a = new BlockPos(0, 0, 0);
		BlockPos b = new BlockPos(1, 0, 0);
		FakeBlocks blocks = new FakeBlocks(access);
		world.put(b, Blocks.STONE.defaultBlockState());
		check(!blocks.free(List.of(a, b)), "a real block means the space is not free");
		check(blocks.free(List.of(a)), "air is free");
		blocks.set(a, Blocks.OBSIDIAN.defaultBlockState());
		blocks.set(b, Blocks.OBSIDIAN.defaultBlockState());
		check(access.get(b).is(Blocks.STONE), "a real block is never replaced");
		check(blocks.owns(a) && !blocks.owns(b), "only placed blocks are owned");
		blocks.set(a, Blocks.SEA_LANTERN.defaultBlockState());
		blocks.restore();
		check(access.get(a).isAir() && access.get(b).is(Blocks.STONE), "restore puts air back");

		blocks.set(a, Blocks.OBSIDIAN.defaultBlockState());
		world.put(a, Blocks.DIRT.defaultBlockState());
		blocks.restore();
		check(access.get(a).is(Blocks.DIRT), "a block the server changed is left alone");

		blocks.replace(b, Blocks.EMERALD_BLOCK.defaultBlockState());
		check(access.get(b).is(Blocks.EMERALD_BLOCK) && blocks.owns(b), "replace covers a real block");
		blocks.replace(b, Blocks.OBSIDIAN.defaultBlockState());
		blocks.restore();
		check(access.get(b).is(Blocks.STONE), "restore puts the covered block back");
	}

	/** The one transform between the Floor 7 coordinates the solvers use and the sim's spot. */
	private static void frames() {
		BlockPos spot = new BlockPos(10, 70, -20);
		for (Direction facing : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
			DeviceSimGeometry.Frame i4 = DeviceSimGeometry.i4Frame(spot, facing);
			for (int cell = 0; cell < 9; cell++) {
				BlockPos sim = DeviceSimGeometry.targetPos(spot, facing, cell);
				BlockPos real = i4.toReal(sim);
				check(real.equals(DeviceSimGeometry.targetPos(new BlockPos(63, 127, 35), Direction.SOUTH, cell)),
					"i4 to real " + facing + " " + cell + " " + real);
				check(i4.toSim(real).equals(sim), "i4 back " + facing + " " + cell);
			}
			DeviceSimGeometry.Frame ss = DeviceSimGeometry.ssFrame(spot, facing);
			for (int cell = 0; cell < 16; cell++) {
				BlockPos real = ss.toReal(DeviceSimGeometry.ssButton(spot, facing, cell));
				check(real.equals(new BlockPos(110, 123 - cell / 4, 92 + cell % 4)), "SS button " + facing + " " + cell + " " + real);
				check(ss.toReal(DeviceSimGeometry.ssLamp(spot, facing, cell)).getX() == 111, "SS lamp " + facing);
			}
			check(ss.toReal(DeviceSimGeometry.ssStart(spot, facing)).equals(new BlockPos(110, 121, 91)), "SS start " + facing);
			Vec3 point = new Vec3(12.25, 71.5, -18.75);
			check(i4.toSim(i4.toReal(point)).distanceTo(point) < 1e-9, "continuous round trip " + facing);
		}
	}

	private static void arrows() {
		AABB near = new AABB(-0.5, 0.5, 4.5, 0.5, 2.5, 5.5);
		List<FakeArrow.Launch> one = FakeArrow.volley(new Vec3(0, 1.62, 0), 0f, 0f, Vec3.ZERO, false);
		check(one.size() == 1 && one.get(0).getDelay() == 0, "one arrow, moving on the shot tick");
		check(Math.abs(one.get(0).getFrom().y - 1.52) < 1e-9 && Math.abs(one.get(0).getFrom().x + 0.01745) < 1e-9,
			"0.1 under the eye and a hair to your right");
		check(Math.abs(one.get(0).getVelocity().length() - 3.0) < 1e-9, "3 blocks a tick");
		FakeArrow.Hit hit = FakeArrow.fly(one.get(0), List.of(near));
		check(hit.getTarget() == 0 && hit.getTicks() == 1, "5 blocks out is hit on the second move, " + hit.getTicks());
		check(FakeArrow.fly(FakeArrow.volley(Vec3.ZERO, 180f, 0f, Vec3.ZERO, false).get(0), List.of(near)).getTarget() == -1,
			"shooting away misses");

		List<FakeArrow.Launch> three = FakeArrow.volley(new Vec3(0, 1.62, 0), 0f, 0f, Vec3.ZERO, true);
		check(three.size() == 3, "Terminator shoots three");
		Vec3 main = three.get(0).getVelocity();
		Vec3 second = new Vec3(main.x * 0.99, main.y * 0.99 - 0.05, main.z * 0.99);
		for (FakeArrow.Launch side : three.subList(1, 3)) {
			check(side.getDelay() == 1, "side arrows leave one tick later");
			check(side.getFrom().distanceTo(three.get(0).getFrom().add(main).add(0, -0.5, 0)) < 1e-9,
				"from half a block under the main arrow's first position");
			double yaw = Math.toDegrees(Math.atan2(-side.getVelocity().x, side.getVelocity().z));
			check(Math.abs(Math.abs(yaw) - 5.5) < 1e-9, "5.5 degrees, was " + yaw);
			check(Math.abs(side.getVelocity().z - second.length() * Math.cos(Math.toRadians(5.5))) < 1e-9, "main arrow's speed");
			check(Math.abs(side.getVelocity().y - 0.05) < 1e-9, "gravity given back");
		}
	}

	/** Aiming from the plate at every sphere the i4 helper draws must hit that cell: solver and arrows agree. */
	private static void solverAims() {
		Vec3 eye = new Vec3(63.5, 127.0625 + 1.62, 35.5);
		List<AABB> cells = new java.util.ArrayList<>();
		for (int i = 0; i < 9; i++) cells.add(new AABB(I4HelperRules.panelPosition(i)));
		StringBuilder missed = new StringBuilder();
		for (int i = 0; i < 9; i++) {
			for (Vec3 spot : I4HelperRules.aimSpots(i, List.of())) {
				Vec3 d = spot.subtract(eye);
				float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
				float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
				int cell = i;
				boolean landed = FakeArrow.volley(eye, yaw, pitch, Vec3.ZERO, true).stream()
					.anyMatch(arrow -> FakeArrow.fly(arrow, cells).getTarget() == cell);
				if (!landed) missed.append(' ').append(i).append('@').append(spot);
			}
		}
		check(missed.isEmpty(), "aim spots that miss:" + missed);
	}

	/** A click inside the cooldown is held and fires the tick the cooldown ends. */
	private static void cooldown() {
		ShotCooldown shots = new ShotCooldown();
		check(shots.click(10, 5), "first click fires");
		check(!shots.click(11, 5) && !shots.click(12, 5), "inside the cooldown nothing fires");
		check(!shots.tick(14, 5), "not before it ends");
		check(shots.tick(15, 5), "the held click fires when it ends");
		check(!shots.tick(20, 5), "only one click is held");
		check(shots.click(21, 5), "a later click fires at once");
	}

	private static void guardAndGate() {
		for (String name : List.of("ServerboundUseItemOnPacket", "ServerboundUseItemPacket", "ServerboundPlayerActionPacket",
			"ServerboundInteractPacket", "ServerboundAttackPacket", "ServerboundSwingPacket")) {
			check(DeviceSimGuard.isBlocked(name), name + " is blocked during a sim");
		}
		check(!DeviceSimGuard.isBlocked("ServerboundMovePlayerPacket") && !DeviceSimGuard.isBlocked("ServerboundChatCommandPacket"),
			"movement and commands still go out");
		check(!DeviceSimGuard.active(), "nothing is blocked without a sim");

		check(DeviceSimGeometry.allowed(true, Island.NONE, false, false), "singleplayer is always allowed");
		check(DeviceSimGeometry.allowed(false, Island.PRIVATE_ISLAND, true, true), "your island with the switch on");
		check(!DeviceSimGeometry.allowed(false, Island.PRIVATE_ISLAND, true, false), "switch off");
		check(!DeviceSimGeometry.allowed(false, Island.HUB, true, true), "not on other islands");
		check(!DeviceSimGeometry.allowed(false, Island.PRIVATE_ISLAND, false, true), "unknown location fails closed");
		check(!DeviceSimGeometry.allowed(false, Island.NONE, true, true), "guest islands report no island");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
