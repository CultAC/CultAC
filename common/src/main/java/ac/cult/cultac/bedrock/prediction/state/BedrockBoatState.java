package ac.cult.cultac.bedrock.prediction.state;

import ac.cult.cultac.utils.data.packetentity.PacketEntity;

public record BedrockBoatState(PacketEntity actor, BedrockBoatProperties properties,
        float angularVelocity, int submergedTicks, int paddleTick, Paddle left, Paddle right,
        boolean analogPaddles) {
    public static BedrockBoatState initial(PacketEntity actor) {
        return new BedrockBoatState(actor, actor.bedrockBoat, 0, 0, 0, Paddle.INITIAL, Paddle.INITIAL, true);
    }

    public BedrockBoatState withProperties(BedrockBoatProperties value, boolean analog) {
        return new BedrockBoatState(actor, value, angularVelocity, submergedTicks, paddleTick, left, right, analog);
    }

    public BedrockBoatState withAngularVelocity(float value) {
        return new BedrockBoatState(actor, properties, value, submergedTicks, paddleTick, left, right, analogPaddles);
    }

    public record Paddle(int lastStroke, int strokeStart, float strength) {
        public static final Paddle INITIAL = new Paddle(-1, -1, 0);

        public Paddle analog(int tick, float force) {
            if (force == 0) return new Paddle(lastStroke, -1, 0);
            if (tick - strokeStart > 9) return new Paddle(lastStroke, tick, force * 3.0F);
            return new Paddle(lastStroke, strokeStart, Math.copySign(Math.max(0, Math.abs(force * 3.0F) - 0.1F), force));
        }

        public Paddle digital(int tick, boolean pressed) {
            if (!pressed) return new Paddle(strokeStart >= 0 ? strokeStart : lastStroke, -1,
                    strength > 0.01F ? strength * 0.5F : 0);
            if (strokeStart < 0) return new Paddle(lastStroke, tick,
                    tick - lastStroke < 10 ? Math.max(2.5F, strength - 0.05F) : 3.0F);
            return new Paddle(lastStroke, strokeStart, tick - strokeStart < 10 ? 3.0F : Math.max(2.5F, strength - 0.05F));
        }
    }
}
