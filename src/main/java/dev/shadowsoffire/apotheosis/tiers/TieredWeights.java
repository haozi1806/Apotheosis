package dev.shadowsoffire.apotheosis.tiers;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableMap;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.shadowsoffire.placebo.reload.WeightedDynamicRegistry.ILuckyWeighted;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.util.random.WeightedEntry;
import net.minecraft.util.random.WeightedEntry.Wrapper;

public record TieredWeights(Map<WorldTier, Weight> weights) {

    /**
     * TieredWeights can be represented in json as a single weight object, which applies for all tiers, or a map from WorldTier->Weight.
     * If opting to provide per-tier weights, weights must be provided for each tier.
     */
    public static MapCodec<TieredWeights> CODEC = Codec.mapEither(Weight.CODEC,
        Codec.simpleMap(WorldTier.CODEC, Weight.CODEC.codec(), StringRepresentable.keys(WorldTier.values())))
        .xmap(e -> e.map(TieredWeights::fillAll, Function.identity()), Either::right).xmap(TieredWeights::new, TieredWeights::weights).validate(TieredWeights::validate);

    private static StreamCodec<ByteBuf, Map<WorldTier, Weight>> MAP_STREAM_CODEC = ByteBufCodecs.map(IdentityHashMap::new, WorldTier.STREAM_CODEC, Weight.STREAM_CODEC, 5);
    public static StreamCodec<ByteBuf, TieredWeights> STREAM_CODEC = MAP_STREAM_CODEC.map(TieredWeights::new, TieredWeights::weights);

    public static record Weight(int weight, float quality) {

        public static Weight ZERO = new Weight(0, 0);

        public static MapCodec<Weight> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            Codec.intRange(0, 1024).fieldOf("weight").forGetter(Weight::weight),
            Codec.floatRange(0, 16).optionalFieldOf("quality", 0F).forGetter(Weight::quality))
            .apply(inst, Weight::new));

        public static StreamCodec<ByteBuf, Weight> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, Weight::weight,
            ByteBufCodecs.FLOAT, Weight::quality,
            Weight::new);

        public int getWeight(float luck) {
            return this.weight + Math.round(luck * this.quality);
        }

    }

    public int getWeight(WorldTier tier, float luck) {
        return this.weights.get(tier).getWeight(luck);
    }

    private static Map<WorldTier, Weight> fillAll(Weight weight) {
        return Arrays.stream(WorldTier.values()).collect(Collectors.toMap(Function.identity(), v -> weight));
    }

    private static DataResult<TieredWeights> validate(TieredWeights weights) {
        return weights.weights.size() == 5 ? DataResult.success(weights) : DataResult.error(() -> "Weights must be provided for each WorldTier.");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static interface Weighted {
        TieredWeights weights();

        /**
         * Helper to wrap this object as a WeightedEntry.
         */
        @SuppressWarnings("unchecked")
        default <T extends Weighted> Wrapper<T> wrap(WorldTier tier, float luck) {
            return wrap((T) this, tier, luck);
        }

        /**
         * Static (and more generic-safe) variant of {@link ILuckyWeighted#wrap(float)}
         */
        static <T extends Weighted> Wrapper<T> wrap(T item, WorldTier tier, float luck) {
            return WeightedEntry.wrap(item, Math.max(0, item.weights().getWeight(tier, luck)));
        }
    }

    public static class Builder {
        ImmutableMap.Builder<WorldTier, Weight> mapBuilder = ImmutableMap.builder();

        public Builder() {
        }

        public Builder with(WorldTier tier, int weight, float quality) {
            this.mapBuilder.put(tier, new Weight(weight, quality));
            return this;
        }

        public TieredWeights build() {
            return new TieredWeights(mapBuilder.build());
        }
    }
}
