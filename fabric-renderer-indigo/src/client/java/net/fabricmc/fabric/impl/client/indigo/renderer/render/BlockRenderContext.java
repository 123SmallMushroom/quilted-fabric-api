/*
 * Copyright 2016, 2017, 2018, 2019 FabricMC
 * Copyright 2022 QuiltMC
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

package net.fabricmc.fabric.impl.client.indigo.renderer.render;

import java.util.function.Consumer;
import java.util.function.Supplier;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

import net.minecraft.block.BlockState;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;

import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.impl.client.indigo.renderer.aocalc.AoCalculator;
import net.fabricmc.fabric.impl.client.indigo.renderer.aocalc.AoLuminanceFix;

/**
 * Context for non-terrain block rendering.
 */
public class BlockRenderContext extends AbstractRenderContext {
	private final BlockRenderInfo blockInfo = new BlockRenderInfo();
	private final AoCalculator aoCalc = new AoCalculator(blockInfo, this::brightness, this::aoLevel);

	private VertexConsumer bufferBuilder;
	private boolean didOutput = false;
	// These are kept as fields to avoid the heap allocation for a supplier.
	// BlockModelRenderer allows the caller to supply both the random object and seed.
	private Random random;
	private long seed;
	private final Supplier<Random> randomSupplier = () -> {
		random.setSeed(seed);
		return random;
	};

	private final AbstractMeshConsumer meshConsumer = new AbstractMeshConsumer(blockInfo, this::outputBuffer, aoCalc, this::transform) {
		@Override
		protected Matrix4f matrix() {
			return matrix;
		}

		@Override
		protected Matrix3f normalMatrix() {
			return normalMatrix;
		}

		@Override
		protected int overlay() {
			return overlay;
		}
	};

	/**
	 * Reuse the fallback consumer from the render context used during chunk rebuild to make it properly
	 * apply the current transforms to vanilla models.
	 */
	private final TerrainFallbackConsumer fallbackConsumer = new TerrainFallbackConsumer(blockInfo, this::outputBuffer, aoCalc, this::transform) {
		@Override
		protected Matrix4f matrix() {
			return matrix;
		}

		@Override
		protected Matrix3f normalMatrix() {
			return normalMatrix;
		}

		@Override
		protected int overlay() {
			return overlay;
		}
	};

	private int brightness(BlockPos pos, BlockState state) {
		if (blockInfo.blockView == null) {
			return LightmapTextureManager.MAX_LIGHT_COORDINATE;
		}

		return WorldRenderer.getLightmapCoordinates(blockInfo.blockView, state, pos);
	}

	private float aoLevel(BlockPos pos, BlockState state) {
		final BlockRenderView blockView = blockInfo.blockView;
		return blockView == null ? 1f : AoLuminanceFix.INSTANCE.apply(blockView, pos, state);
	}

	private VertexConsumer outputBuffer(RenderLayer renderLayer) {
		didOutput = true;
		return bufferBuilder;
	}

	public boolean render(BlockRenderView blockView, BakedModel model, BlockState state, BlockPos pos, MatrixStack matrixStack, VertexConsumer buffer, Random random, long seed, int overlay) {
		this.bufferBuilder = buffer;
		this.matrix = matrixStack.peek().getPositionMatrix();
		this.normalMatrix = matrixStack.peek().getNormalMatrix();
		this.random = random;
		this.seed = seed;

		this.overlay = overlay;
		this.didOutput = false;
		aoCalc.clear();
		blockInfo.setBlockView(blockView);
		blockInfo.prepareForBlock(state, pos, model.useAmbientOcclusion());

		((FabricBakedModel) model).emitBlockQuads(blockView, state, pos, randomSupplier, this);

		blockInfo.release();
		this.bufferBuilder = null;
		this.random = null;
		this.seed = seed;

		return didOutput;
	}

	@Override
	public Consumer<Mesh> meshConsumer() {
		return meshConsumer;
	}

	@Override
	public Consumer<BakedModel> fallbackConsumer() {
		return fallbackConsumer;
	}

	@Override
	public QuadEmitter getEmitter() {
		return meshConsumer.getEmitter();
	}
}
