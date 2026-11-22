package com.fpsdisplay;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.vehicle.MinecartTNT;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.Predicate;

/**
 * Press V while looking at a block:
 *  1. places a rail on the block you look at
 *  2. places a TNT minecart on the rail
 *  3. places fire (flint and steel) between you and the cart
 *  4. aims and shoots the loaded crossbow through the fire into the cart
 *
 * The mod is "disabled" automatically while any required item is missing.
 */
public class FpsDisplayClient implements ClientModInitializer {

	private enum Step { IDLE, SHOOT }

	/** Ticks from pressing V until the arrow is fired (20 ticks = 1 second). */
	private static final int SHOOT_DELAY_TICKS = 4;

	private static final Predicate<ItemStack> IS_RAIL = s -> s.is(ItemTags.RAILS);
	private static final Predicate<ItemStack> IS_CART = s -> s.is(Items.TNT_MINECART);
	private static final Predicate<ItemStack> IS_FLINT = s -> s.is(Items.FLINT_AND_STEEL);
	private static final Predicate<ItemStack> IS_CROSSBOW = s -> s.is(Items.CROSSBOW) && CrossbowItem.isCharged(s);

	private KeyMapping keyV;
	private Step step = Step.IDLE;
	private int wait = 0;
	private int timeout = 0;
	private int relights = 0;
	private boolean wasEnabled = true;

	private BlockHitResult target;
	private BlockPos railPos;
	private BlockPos firePos;
	private MinecartTNT cart;

	@Override
	public void onInitializeClient() {
		keyV = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.fpsdisplay.crossbowcart", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V,
				KeyMapping.Category.MISC));
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}

	// ------------------------------------------------------------------ tick

	private void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.gameMode == null) {
			step = Step.IDLE;
			while (keyV.consumeClick()) { }
			return;
		}

		String missing = missingItems(player);
		boolean enabled = missing == null;

		// Mod disables itself while items are missing, re-enables when they are back.
		if (!enabled && step == Step.IDLE) {
			while (keyV.consumeClick()) {
				msg(player, "Crossbow cart disabled - missing: " + missingItems(player));
			}
			wasEnabled = false;
			return;
		}
		if (enabled && !wasEnabled && step == Step.IDLE) {
			msg(player, "Crossbow cart enabled - press V while looking at a block");
		}
		if (step == Step.IDLE) wasEnabled = enabled;

		if (step == Step.IDLE) {
			while (keyV.consumeClick()) {
				start(mc, player);
			}
			return;
		}

		// Pressing V again cancels a running sequence
		while (keyV.consumeClick()) {
			abort(player, "Cancelled");
			return;
		}

		if (wait > 0) { wait--; return; }

		try {
			run(mc, player);
		} catch (Exception e) {
			abort(player, "Error: " + e.getClass().getSimpleName());
		}
	}

	private void start(Minecraft mc, LocalPlayer player) {
		HitResult hit = mc.hitResult;
		if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) {
			msg(player, "Look at a block first");
			return;
		}
		target = bhr;
		railPos = bhr.getBlockPos().relative(bhr.getDirection());
		firePos = null;
		cart = null;
		timeout = 0;
		relights = 0;
		try {
			placeEverything(mc, player);
		} catch (Exception e) {
			abort(player, "Error: " + e.getClass().getSimpleName());
		}
	}

	/** Rail, TNT minecart and fire are all sent in the same tick; packets are processed in order. */
	private void placeEverything(Minecraft mc, LocalPlayer player) {
		MultiPlayerGameMode gm = mc.gameMode;
		Level level = mc.level;

		// 1) rail
		if (!select(mc, player, IS_RAIL)) { abort(player, "No rail"); return; }
		gm.useItemOn(player, InteractionHand.MAIN_HAND, target);
		player.swing(InteractionHand.MAIN_HAND);
		if (!(level.getBlockState(railPos).getBlock() instanceof BaseRailBlock)) {
			abort(player, "Rail could not be placed there");
			return;
		}

		// 2) TNT minecart on the rail
		if (!select(mc, player, IS_CART)) { abort(player, "No TNT minecart"); return; }
		Vec3 railTop = new Vec3(railPos.getX() + 0.5, railPos.getY() + 0.5, railPos.getZ() + 0.5);
		gm.useItemOn(player, InteractionHand.MAIN_HAND,
				new BlockHitResult(railTop, Direction.UP, railPos, false));
		player.swing(InteractionHand.MAIN_HAND);

		// 3) fire between player and cart (position computed from the rail, cart need not exist yet)
		Vec3 cartCenter = new Vec3(railPos.getX() + 0.5, railPos.getY() + 0.3, railPos.getZ() + 0.5);
		firePos = chooseFirePos(level, player, cartCenter);
		if (firePos == null) { abort(player, "No valid spot for fire between you and the cart"); return; }
		if (!select(mc, player, IS_FLINT)) { abort(player, "No flint and steel"); return; }
		BlockPos ground = firePos.below();
		Vec3 face = new Vec3(ground.getX() + 0.5, ground.getY() + 1.0, ground.getZ() + 0.5);
		gm.useItemOn(player, InteractionHand.MAIN_HAND,
				new BlockHitResult(face, Direction.UP, ground, false));
		player.swing(InteractionHand.MAIN_HAND);

		step = Step.SHOOT;
		wait = SHOOT_DELAY_TICKS - 1;
	}

	private void run(Minecraft mc, LocalPlayer player) {
		Level level = mc.level;
		timeout++;

		if (cart == null || cart.isRemoved()) cart = findCart(level);
		boolean fireOk = level.getBlockState(firePos).is(BlockTags.FIRE);

		// fire not there yet (lag / failed placement): try to light it again every 4 ticks
		if (!fireOk && timeout % 4 == 0 && relights < 5) {
			relights++;
			if (select(mc, player, IS_FLINT)) {
				BlockPos ground = firePos.below();
				Vec3 face = new Vec3(ground.getX() + 0.5, ground.getY() + 1.0, ground.getZ() + 0.5);
				mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
						new BlockHitResult(face, Direction.UP, ground, false));
			}
		}

		// only shoot once BOTH the cart and the fire are confirmed by the server
		if (cart == null || !fireOk) {
			if (timeout > 100) { abort(player, "timeout"); return; }
			wait = 0;
			return;
		}

		if (!select(mc, player, IS_CROSSBOW)) { abort(player, "No loaded crossbow"); return; }
		lookAt(player, cart.position().add(0, 0.35, 0));
		mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
		step = Step.IDLE;
	}

	// --------------------------------------------------------------- helpers

	/** @return null when everything is present, otherwise a list of what is missing. */
	private String missingItems(LocalPlayer player) {
		StringBuilder sb = new StringBuilder();
		if (!has(player, IS_CART)) sb.append("TNT minecart, ");
		if (!has(player, IS_FLINT)) sb.append("flint and steel, ");
		if (!has(player, IS_CROSSBOW)) sb.append("loaded crossbow, ");
		if (!has(player, IS_RAIL)) sb.append("rail, ");
		if (sb.length() == 0) return null;
		return sb.substring(0, sb.length() - 2);
	}

	private boolean has(LocalPlayer player, Predicate<ItemStack> p) {
		var inv = player.getInventory();
		for (int i = 0; i < 36; i++) {
			if (p.test(inv.getItem(i))) return true;
		}
		return false;
	}

	/** Selects a matching stack in the hotbar, swapping it in from the inventory if needed. */
	private boolean select(Minecraft mc, LocalPlayer player, Predicate<ItemStack> p) {
		var inv = player.getInventory();
		for (int i = 0; i < 9; i++) {
			if (p.test(inv.getItem(i))) {
				inv.setSelectedSlot(i);
				return true;
			}
		}
		for (int i = 9; i < 36; i++) {
			if (p.test(inv.getItem(i))) {
				int hotbar = inv.getSelectedSlot();
				mc.gameMode.handleInventoryMouseClick(
						player.inventoryMenu.containerId, i, hotbar, ClickType.SWAP, player);
				return p.test(inv.getItem(hotbar));
			}
		}
		return false;
	}

	private MinecartTNT findCart(Level level) {
		AABB box = new AABB(railPos).inflate(0.5);
		List<MinecartTNT> list = level.getEntitiesOfClass(MinecartTNT.class, box);
		return list.isEmpty() ? null : list.get(0);
	}

	/** The air block on the line between the player's eyes and the cart, right in front of the cart. */
	private BlockPos chooseFirePos(Level level, LocalPlayer player, Vec3 c) {
		Vec3 eye = player.getEyePosition();
		Vec3 dir = eye.subtract(c).normalize();

		BlockPos cartCell = BlockPos.containing(c);
		for (double d = 0.3; d < 3.0; d += 0.1) {
			BlockPos p = BlockPos.containing(c.add(dir.scale(d)));
			if (p.equals(cartCell)) continue;
			if (isFireSpot(level, p) && !p.equals(BlockPos.containing(eye))) return p;
			break;
		}
		// fallback: neighbour of the rail on the player's side, same height
		Vec3 diff = player.position().subtract(Vec3.atCenterOf(railPos));
		Direction side = Math.abs(diff.x) > Math.abs(diff.z)
				? (diff.x > 0 ? Direction.EAST : Direction.WEST)
				: (diff.z > 0 ? Direction.SOUTH : Direction.NORTH);
		BlockPos p = railPos.relative(side);
		return isFireSpot(level, p) ? p : null;
	}

	private boolean isFireSpot(Level level, BlockPos p) {
		return level.getBlockState(p).isAir()
				&& level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP);
	}

	private void lookAt(LocalPlayer player, Vec3 pos) {
		Vec3 eye = player.getEyePosition();
		double dx = pos.x - eye.x;
		double dy = pos.y - eye.y;
		double dz = pos.z - eye.z;
		double horiz = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
		player.setYRot(yaw);
		player.setXRot(pitch);
	}

	private void abort(LocalPlayer player, String reason) {
		step = Step.IDLE;
		msg(player, "Crossbow cart stopped: " + reason);
	}

	/** Messages removed on request - intentionally does nothing. */
	private void msg(LocalPlayer player, String text) {
	}
}
