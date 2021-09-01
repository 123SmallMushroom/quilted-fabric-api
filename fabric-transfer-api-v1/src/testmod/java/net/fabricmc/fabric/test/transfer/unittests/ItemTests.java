/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.fabric.test.transfer.unittests;

import java.util.stream.IntStream;

import org.jetbrains.annotations.Nullable;

import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SidedInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.Direction;

import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;

/**
 * Tests for the item transfer APIs.
 */
class ItemTests {
	public static void run() {
		testInventoryWrappers();
		testLimitedStackCountInventory();
		testLimitedStackCountItem();
	}

	private static void testInventoryWrappers() {
		ItemVariant emptyBucket = ItemVariant.of(Items.BUCKET);
		TestSidedInventory testInventory = new TestSidedInventory();
		checkComparatorOutput(testInventory, null);

		// Create a few wrappers.
		InventoryStorage unsidedWrapper = InventoryStorage.of(testInventory, null);
		InventoryStorage downWrapper = InventoryStorage.of(testInventory, Direction.DOWN);
		InventoryStorage upWrapper = InventoryStorage.of(testInventory, Direction.UP);

		// Make sure querying a new wrapper returns the same one.
		if (InventoryStorage.of(testInventory, null) != unsidedWrapper) throw new AssertionError("Wrappers should be ==.");

		for (int iter = 0; iter < 2; ++iter) {
			// First time, abort.
			// Second time, commit.
			try (Transaction transaction = Transaction.openOuter()) {
				// Insert bucket from down - should fail.
				if (downWrapper.insert(emptyBucket, 1, transaction) != 0) throw new AssertionError("Bucket should not have been inserted.");
				// Insert bucket unsided - should go in slot 1 (isValid returns false for slot 0).
				if (unsidedWrapper.insert(emptyBucket, 1, transaction) != 1) throw new AssertionError("Failed to insert bucket.");
				if (!testInventory.getStack(0).isEmpty()) throw new AssertionError("Slot 0 should have been empty.");
				if (!stackEquals(testInventory.getStack(1), Items.BUCKET, 1)) throw new AssertionError("Slot 1 should have been a bucket.");
				// The bucket should be extractable from any side but the top.
				if (!emptyBucket.equals(StorageUtil.findExtractableResource(unsidedWrapper, transaction))) throw new AssertionError("Bucket should be extractable from unsided wrapper.");
				if (!emptyBucket.equals(StorageUtil.findExtractableResource(downWrapper, transaction))) throw new AssertionError("Bucket should be extractable from down wrapper.");
				if (StorageUtil.findExtractableResource(upWrapper, transaction) != null) throw new AssertionError("Bucket should NOT be extractable from up wrapper.");

				if (iter == 1) {
					// Commit the second time only.
					transaction.commit();
				}
			}
		}

		// Check commit.
		if (!testInventory.getStack(0).isEmpty()) throw new AssertionError("Slot 0 should have been empty.");
		if (!testInventory.getStack(1).isOf(Items.BUCKET) || testInventory.getStack(1).getCount() != 1) throw new AssertionError("Slot 1 should have been a bucket.");

		checkComparatorOutput(testInventory, null);
	}

	private static boolean stackEquals(ItemStack stack, Item item, int count) {
		return stack.getItem() == item && stack.getCount() == count;
	}

	private static class TestSidedInventory extends SimpleInventory implements SidedInventory {
		private static final int[] SLOTS = IntStream.range(0, 3).toArray();

		TestSidedInventory() {
			super(SLOTS.length);
		}

		@Override
		public int[] getAvailableSlots(Direction side) {
			return SLOTS;
		}

		@Override
		public boolean isValid(int slot, ItemStack stack) {
			return slot != 0 || !stack.isOf(Items.BUCKET); // can't have buckets in slot 0.
		}

		@Override
		public boolean canInsert(int slot, ItemStack stack, @Nullable Direction dir) {
			return dir != Direction.DOWN;
		}

		@Override
		public boolean canExtract(int slot, ItemStack stack, Direction dir) {
			return dir != Direction.UP;
		}
	}

	/**
	 * Test insertion when {@link Inventory#getMaxCountPerStack()} is the bottleneck.
	 */
	private static void testLimitedStackCountInventory() {
		ItemVariant diamond = ItemVariant.of(Items.DIAMOND);
		LimitedStackCountInventory inventory = new LimitedStackCountInventory(diamond.toStack(), diamond.toStack(), diamond.toStack());
		InventoryStorage wrapper = InventoryStorage.of(inventory, null);

		// Should only be able to insert 2 diamonds per stack * 3 stacks = 6 diamonds.
		try (Transaction transaction = Transaction.openOuter()) {
			if (wrapper.insert(diamond, 1000, transaction) != 6) {
				throw new AssertionError("Only 6 diamonds should have been inserted.");
			}

			checkComparatorOutput(inventory, transaction);
		}
	}

	/**
	 * Test insertion when {@link Item#getMaxCount()} is the bottleneck.
	 */
	private static void testLimitedStackCountItem() {
		ItemVariant diamondPickaxe = ItemVariant.of(Items.DIAMOND_PICKAXE);
		LimitedStackCountInventory inventory = new LimitedStackCountInventory(5);
		InventoryStorage wrapper = InventoryStorage.of(inventory, null);

		// Should only be able to insert 5 pickaxes, as the item limits stack counts to 1.
		try (Transaction transaction = Transaction.openOuter()) {
			if (wrapper.insert(diamondPickaxe, 1000, transaction) != 5) {
				throw new AssertionError("Only 5 pickaxes should have been inserted.");
			}

			checkComparatorOutput(inventory, transaction);
		}
	}

	private static class LimitedStackCountInventory extends SimpleInventory {
		LimitedStackCountInventory(int size) {
			super(size);
		}

		LimitedStackCountInventory(ItemStack... stacks) {
			super(stacks);
		}

		@Override
		public int getMaxCountPerStack() {
			return 3;
		}
	}

	private static void checkComparatorOutput(Inventory inventory, @Nullable Transaction transaction) {
		Storage<ItemVariant> storage = InventoryStorage.of(inventory, null);

		int vanillaOutput = ScreenHandler.calculateComparatorOutput(inventory);
		int transferApiOutput = StorageUtil.calculateComparatorOutput(storage, transaction);

		if (vanillaOutput != transferApiOutput) {
			String error = String.format(
					"Vanilla and Transfer API comparator outputs should have been identical. Vanilla: %d. Transfer API: %d.",
					vanillaOutput,
					transferApiOutput
			);
			throw new AssertionError(error);
		}
	}
}
